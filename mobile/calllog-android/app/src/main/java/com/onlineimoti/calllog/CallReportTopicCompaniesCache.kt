package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Keeps the last verified company list for the active server account so a note can
 * still be assigned while the phone is offline. The account scope is hashed; the
 * access token itself is never stored in this cache.
 */
internal data class CachedTopicCompanies(
    val companies: List<CallReportTopicCompany>,
    val updatedAtMs: Long,
)

internal enum class TopicCompaniesSource {
    ONLINE,
    CACHED,
}

internal data class TopicCompaniesLoadResult(
    val companies: List<CallReportTopicCompany>,
    val source: TopicCompaniesSource,
    val updatedAtMs: Long,
)

internal object CallReportTopicCompaniesRepository {
    fun load(context: Context, config: AppConfig): TopicCompaniesLoadResult {
        CallReportTopicCompaniesCache.read(context, config)?.let { cached ->
            return TopicCompaniesLoadResult(
                companies = cached.companies,
                source = TopicCompaniesSource.CACHED,
                updatedAtMs = cached.updatedAtMs,
            )
        }
        return refresh(context, config)
    }

    /** Replaces the account-scoped cache with the current authorized firms. */
    fun refresh(context: Context, config: AppConfig): TopicCompaniesLoadResult {
        val serverCompanies = CallReportTopicCompaniesClient.fetch(config)
        val companies = AccountMutationOutbox.applyPendingCompanyOverrides(
            context.applicationContext,
            serverCompanies,
        )
        val updatedAtMs = System.currentTimeMillis()
        CallReportTopicCompaniesCache.save(context, config, companies, updatedAtMs)
        return TopicCompaniesLoadResult(companies, TopicCompaniesSource.ONLINE, updatedAtMs)
    }
}

internal object CallReportTopicCompaniesCache {
    private const val PREFS = "callreport_topic_companies_cache"
    private const val KEY_SCOPE = "account_scope"
    private const val KEY_UPDATED_AT = "updated_at_ms"
    private const val KEY_COMPANIES = "companies"

    fun save(
        context: Context,
        config: AppConfig,
        companies: List<CallReportTopicCompany>,
        updatedAtMs: Long = System.currentTimeMillis(),
    ) {
        val scope = scopeFor(config) ?: return
        val payload = JSONArray().apply {
            companies
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
                .sortedBy { it.name.lowercase() }
                .forEach { company ->
                    put(JSONObject().apply {
                        put("id", company.id)
                        put("name", company.name)
                        put("role", company.role)
                        put("can_manage_users", company.canManageUsers)
                        put("eik", company.eik)
                        put("created_at_ms", company.createdAtMs)
                        put("updated_at_ms", company.updatedAtMs)
                    })
                }
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCOPE, scope)
            .putLong(KEY_UPDATED_AT, updatedAtMs)
            .putString(KEY_COMPANIES, payload.toString())
            .commit()
    }

    /** Optimistically updates an already cached company without changing its role. */
    fun updateCompany(
        context: Context,
        config: AppConfig,
        companyId: String,
        name: String,
        eik: String,
    ): Boolean {
        val targetId = companyId.trim()
        val safeName = name.trim()
        if (targetId.isBlank() || safeName.isBlank()) return false
        val cached = read(context.applicationContext, config) ?: return false
        var found = false
        val now = System.currentTimeMillis()
        val companies = cached.companies.map { company ->
            if (company.id != targetId) return@map company
            found = true
            company.copy(
                name = safeName,
                eik = eik.trim(),
                updatedAtMs = maxOf(company.updatedAtMs, now),
            )
        }
        if (found) {
            save(
                context = context.applicationContext,
                config = config,
                companies = companies,
                updatedAtMs = now,
            )
        }
        return found
    }

    fun read(context: Context, config: AppConfig): CachedTopicCompanies? {
        val scope = scopeFor(config) ?: return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SCOPE, "").orEmpty() != scope) return null

        val raw = prefs.getString(KEY_COMPANIES, "").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        val companies = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim().ifBlank { id }
                val role = when (item.optString("role", "member").trim().lowercase()) {
                    "owner" -> "owner"
                    "admin" -> "admin"
                    else -> "member"
                }
                val canManageUsers = item.optBoolean("can_manage_users", role == "owner" || role == "admin")
                if (id.isNotBlank()) {
                    add(
                        CallReportTopicCompany(
                            id = id,
                            name = name,
                            role = role,
                            canManageUsers = canManageUsers,
                            eik = item.optString("eik").trim(),
                            createdAtMs = item.optLong("created_at_ms", 0L),
                            updatedAtMs = item.optLong("updated_at_ms", 0L),
                        ),
                    )
                }
            }
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        return CachedTopicCompanies(
            companies = companies,
            updatedAtMs = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    private fun scopeFor(config: AppConfig): String? {
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        val token = config.accessToken.trim()
        if (baseUrl.isBlank() || token.isBlank()) return null
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$baseUrl|$token".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}
