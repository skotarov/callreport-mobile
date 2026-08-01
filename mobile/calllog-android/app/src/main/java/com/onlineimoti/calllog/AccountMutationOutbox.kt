package com.onlineimoti.calllog

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Durable offline-first queue for small authenticated account mutations.
 *
 * Notes, CRM markers and company-topic notes keep using their existing specialized
 * outboxes. This queue is intentionally limited to profile/company metadata so the
 * existing synchronization contracts remain unchanged.
 */
internal object AccountMutationOutbox {
    private const val PREFS = "relationship_manager_account_mutation_outbox"
    private const val KEY_OPERATIONS = "operations_v1"
    private const val KEY_LAST_FAILURE = "last_failure"
    private const val UNIQUE_WORK = "relationship_manager_account_mutation_sync"
    private const val MAX_BATCH_SIZE = 20
    private val lock = Any()

    internal enum class Kind(val wireName: String) {
        PROFILE_NAME("profile_name"),
        COMPANY_UPDATE("company_update");

        companion object {
            fun fromWireName(value: String): Kind? = entries.firstOrNull { it.wireName == value }
        }
    }

    internal data class Operation(
        val id: String,
        val accountScope: String,
        val kind: Kind,
        val companyId: String = "",
        val name: String,
        val eik: String = "",
        val updatedAtMs: Long,
    )

    fun enqueueProfileName(context: Context, displayName: String): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val safeName = displayName.trim()
        require(safeName.isNotBlank()) { "Въведи име." }
        require(safeName.length <= 120) { "Името е прекалено дълго." }
        val scope = currentScope(appContext)
        require(scope.isNotBlank()) { "Първо влез в профила." }

        CompanySessionStore.updateUserName(appContext, safeName)
        enqueue(
            appContext,
            Operation(
                id = "$scope:profile_name",
                accountScope = scope,
                kind = Kind.PROFILE_NAME,
                name = safeName,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    fun enqueueCompanyUpdate(
        context: Context,
        companyId: String,
        name: String,
        eik: String,
    ): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val safeCompanyId = companyId.trim()
        val safeName = name.trim()
        val safeEik = eik.trim()
        require(safeCompanyId.isNotBlank()) { "Липсва фирма за редактиране." }
        require(safeName.isNotBlank()) { "Името на фирмата е задължително." }
        require(safeName.length <= 120) { "Името може да бъде най-много 120 знака." }
        require(safeEik.length <= 20) { "ЕИК/Булстат може да бъде най-много 20 знака." }
        val scope = currentScope(appContext)
        require(scope.isNotBlank()) { "Първо влез в профила." }

        val config = ConfigStore.load(appContext)
        CallReportTopicCompaniesCache.updateCompany(
            context = appContext,
            config = config,
            companyId = safeCompanyId,
            name = safeName,
            eik = safeEik,
        )
        enqueue(
            appContext,
            Operation(
                id = "$scope:company:$safeCompanyId",
                accountScope = scope,
                kind = Kind.COMPANY_UPDATE,
                companyId = safeCompanyId,
                name = safeName,
                eik = safeEik,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    fun schedulePending(context: Context, replace: Boolean = false) {
        val appContext = context.applicationContext
        if (!hasPendingForCurrentAccount(appContext)) return
        val request = OneTimeWorkRequestBuilder<AccountMutationWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_WORK,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun pendingCountForCurrentAccount(context: Context): Int {
        val scope = currentScope(context.applicationContext)
        if (scope.isBlank()) return 0
        return synchronized(lock) { readLocked(context).count { it.accountScope == scope } }
    }

    fun lastFailure(context: Context): String = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_FAILURE, "")
        .orEmpty()
        .trim()

    internal fun takeCurrentBatch(context: Context): List<Operation> {
        val scope = currentScope(context.applicationContext)
        if (scope.isBlank()) return emptyList()
        return synchronized(lock) {
            readLocked(context)
                .asSequence()
                .filter { it.accountScope == scope }
                .sortedBy { it.updatedAtMs }
                .take(MAX_BATCH_SIZE)
                .toList()
        }
    }

    internal fun acknowledge(context: Context, operationIds: Collection<String>) {
        if (operationIds.isEmpty()) return
        val ids = operationIds.toSet()
        synchronized(lock) {
            writeLocked(context, readLocked(context).filterNot { it.id in ids })
        }
    }

    internal fun recordFailure(context: Context, message: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_FAILURE, message.trim())
            .commit()
    }

    internal fun clearFailure(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_FAILURE)
            .commit()
    }

    private fun enqueue(context: Context, operation: Operation) {
        synchronized(lock) {
            val operations = readLocked(context)
                .filterNot { it.id == operation.id }
                .toMutableList()
            operations += operation
            check(writeLocked(context, operations)) {
                "Промяната не можа да бъде записана локално."
            }
        }
        schedulePending(context)
    }

    private fun hasPendingForCurrentAccount(context: Context): Boolean {
        val scope = currentScope(context.applicationContext)
        if (scope.isBlank()) return false
        return synchronized(lock) { readLocked(context).any { it.accountScope == scope } }
    }

    private fun readLocked(context: Context): List<Operation> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OPERATIONS, "[]")
            .orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toOperation()?.let(::add)
            }
        }
    }

    private fun writeLocked(context: Context, operations: List<Operation>): Boolean {
        val payload = JSONArray().apply { operations.forEach { put(it.toJson()) } }
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OPERATIONS, payload.toString())
            .commit()
    }

    private fun Operation.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("account_scope", accountScope)
        put("kind", kind.wireName)
        put("company_id", companyId)
        put("name", name)
        put("eik", eik)
        put("updated_at_ms", updatedAtMs)
    }

    private fun JSONObject.toOperation(): Operation? {
        val id = optString("id").trim()
        val scope = optString("account_scope").trim()
        val kind = Kind.fromWireName(optString("kind").trim()) ?: return null
        val name = optString("name").trim()
        if (id.isBlank() || scope.isBlank() || name.isBlank()) return null
        val companyId = optString("company_id").trim()
        if (kind == Kind.COMPANY_UPDATE && companyId.isBlank()) return null
        return Operation(
            id = id,
            accountScope = scope,
            kind = kind,
            companyId = companyId,
            name = name,
            eik = optString("eik").trim(),
            updatedAtMs = optLong("updated_at_ms", 0L)
                .takeIf { it > 0L }
                ?: System.currentTimeMillis(),
        )
    }

    private fun currentScope(context: Context): String {
        val config = ConfigStore.load(context.applicationContext)
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        val profile = CompanySessionStore.profileScopeKey(context.applicationContext)
        if (baseUrl.isBlank() || profile.isBlank()) return ""
        return sha256("$baseUrl|$profile")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
