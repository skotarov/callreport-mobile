package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One durable call-note record per concrete call and company. */
internal data class QueuedCompanyCallNote(
    val clientEventId: String,
    val companyId: String,
    val phone: String,
    val direction: String,
    val callAtMs: Long,
    val durationSeconds: Long,
    val note: String,
    val contactName: String,
    val updatedAtMs: Long,
    val authorProfileId: String = "",
    val authorName: String = "",
) {
    fun toHistoryEvent(): CallReportHistoryEvent = CallReportHistoryEvent(
        clientEventId = clientEventId,
        communicationType = "note",
        phone = phone,
        direction = direction,
        occurredAtMs = callAtMs,
        durationSeconds = durationSeconds,
        note = note,
        contactName = contactName,
        createdAtMs = updatedAtMs,
        updatedAtMs = updatedAtMs,
        companyId = companyId,
        authorProfileId = authorProfileId,
        authorBrokerName = authorName,
        isMine = true,
        canEdit = true,
    )

    fun toSyncEvent(context: Context): CallReportTopicSyncEvent = CallReportTopicSyncEvent(
        clientEventId = clientEventId,
        companyId = companyId,
        phone = phone,
        direction = direction,
        occurredAtMs = callAtMs,
        durationSeconds = durationSeconds,
        note = note,
        contactName = contactName,
        deviceId = CallReportInstallationId.get(context),
        appVersion = BuildConfig.VERSION_NAME,
    )
}

internal object CompanyCallNoteOutbox {
    private const val PREFS = "company_call_note_outbox"
    private const val KEY_OPERATIONS = "operations_v1"
    private const val KEY_LAST_FAILURE = "last_failure"
    private const val UNIQUE_WORK = "company_call_note_sync"
    private val lock = Any()

    fun enqueue(
        context: Context,
        phone: String,
        note: String,
        direction: String,
        callAtMs: Long,
        durationSeconds: Long,
        companyId: String,
        existingClientEventId: String = "",
        authorProfileId: String = "",
        authorName: String = "",
        scheduleWorker: Boolean = true,
    ): Boolean {
        val appContext = context.applicationContext
        val target = companyId.trim()
        val stableCallId = LocalNotesFileStore.clientNoteIdForCall(phone, callAtMs, direction)
        if (PhoneNormalizer.key(phone).isBlank() || target.isBlank() || callAtMs <= 0L || stableCallId.isBlank()) return false
        val eventId = existingClientEventId.trim().ifBlank {
            val encodedCompany = Base64.encodeToString(
                target.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            ServerRecordIndex.callNoteEventId(appContext, "$stableCallId-company-$encodedCompany")
        }
        val session = CompanySessionStore.load(appContext)
        val operation = QueuedCompanyCallNote(
            clientEventId = eventId,
            companyId = target,
            phone = phone,
            direction = direction,
            callAtMs = callAtMs,
            durationSeconds = durationSeconds.coerceAtLeast(0L),
            note = note.trim(),
            contactName = ContactGroupFilter.resolveDisplayName(appContext, phone).orEmpty().trim(),
            updatedAtMs = System.currentTimeMillis(),
            authorProfileId = authorProfileId.trim().ifBlank { session?.userId.orEmpty() },
            authorName = authorName.trim().ifBlank { session?.userName.orEmpty() },
        )
        ServerRecordIndex.markPending(appContext, eventId)
        synchronized(lock) {
            val updated = readLocked(appContext).filterNot { it.clientEventId == eventId }.toMutableList()
            updated += operation
            writeLocked(appContext, updated)
        }
        if (scheduleWorker) requestSyncNow(appContext)
        notifyChanged(appContext)
        return true
    }

    fun requestSyncNow(context: Context) {
        if (!hasPending(context)) return
        val request = OneTimeWorkRequestBuilder<CompanyCallNoteWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun pendingEvents(context: Context, phones: Collection<String>): List<CallReportHistoryEvent> {
        val keys = phones.mapTo(hashSetOf(), PhoneNormalizer::key).filterTo(hashSetOf()) { it.isNotBlank() }
        if (keys.isEmpty()) return emptyList()
        return synchronized(lock) {
            readLocked(context.applicationContext)
                .filter { PhoneNormalizer.key(it.phone) in keys }
                .map(QueuedCompanyCallNote::toHistoryEvent)
        }
    }

    fun pendingClientEventIds(context: Context): Set<String> = synchronized(lock) {
        readLocked(context.applicationContext).mapTo(linkedSetOf()) { it.clientEventId }
    }

    fun lastFailure(context: Context): String = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_FAILURE, "")
        .orEmpty()
        .trim()

    internal fun recordFailure(context: Context, message: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_FAILURE, message.trim()).commit()
    }

    internal fun clearFailure(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST_FAILURE).commit()
    }

    fun isCallPending(context: Context, phone: String, direction: String, callAtMs: Long): Boolean {
        val key = PhoneNormalizer.key(phone)
        if (key.isBlank() || callAtMs <= 0L) return false
        return synchronized(lock) {
            readLocked(context.applicationContext).any {
                PhoneNormalizer.key(it.phone) == key && it.callAtMs == callAtMs &&
                    (direction.isBlank() || it.direction.isBlank() || it.direction == direction)
            }
        }
    }

    internal fun takeBatch(context: Context, limit: Int): List<QueuedCompanyCallNote> = synchronized(lock) {
        readLocked(context.applicationContext).sortedBy { it.updatedAtMs }.take(limit.coerceIn(1, 50))
    }

    internal fun acknowledge(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val confirmed = ids.toSet()
        synchronized(lock) {
            writeLocked(context.applicationContext, readLocked(context.applicationContext).filterNot { it.clientEventId in confirmed })
        }
    }

    internal fun hasPending(context: Context): Boolean = synchronized(lock) {
        readLocked(context.applicationContext).isNotEmpty()
    }

    private fun readLocked(context: Context): List<QueuedCompanyCallNote> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OPERATIONS, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.toOperation()?.let(::add)
        }
    }

    private fun writeLocked(context: Context, operations: List<QueuedCompanyCallNote>) {
        val payload = JSONArray().apply { operations.forEach { put(it.toJson()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OPERATIONS, payload.toString()).commit()
    }

    private fun QueuedCompanyCallNote.toJson(): JSONObject = JSONObject().apply {
        put("client_event_id", clientEventId)
        put("company_id", companyId)
        put("phone", phone)
        put("direction", direction)
        put("call_at_ms", callAtMs)
        put("duration_seconds", durationSeconds)
        put("note", note)
        put("contact_name", contactName)
        put("updated_at_ms", updatedAtMs)
        if (authorProfileId.isNotBlank()) put("author_profile_id", authorProfileId)
        if (authorName.isNotBlank()) put("author_name", authorName)
    }

    private fun JSONObject.toOperation(): QueuedCompanyCallNote? {
        val eventId = optString("client_event_id").trim()
        val companyId = optString("company_id").trim()
        val phone = optString("phone").trim()
        val callAtMs = optLong("call_at_ms", 0L)
        if (eventId.isBlank() || companyId.isBlank() || PhoneNormalizer.key(phone).isBlank() || callAtMs <= 0L) return null
        return QueuedCompanyCallNote(
            clientEventId = eventId,
            companyId = companyId,
            phone = phone,
            direction = optString("direction").trim(),
            callAtMs = callAtMs,
            durationSeconds = optLong("duration_seconds", 0L).coerceAtLeast(0L),
            note = optString("note"),
            contactName = optString("contact_name").trim(),
            updatedAtMs = optLong("updated_at_ms", 0L).takeIf { it > 0L } ?: System.currentTimeMillis(),
            authorProfileId = optString("author_profile_id").trim(),
            authorName = optString("author_name").trim(),
        )
    }

    private fun notifyChanged(context: Context) {
        context.sendBroadcast(Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(context.packageName))
    }
}

class CompanyCallNoteWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = ConfigStore.load(applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) {
            CompanyCallNoteOutbox.recordFailure(applicationContext, "Липсва активна сървърна връзка.")
            return@withContext Result.retry()
        }
        try {
            while (CompanyCallNoteOutbox.hasPending(applicationContext)) {
                val batch = CompanyCallNoteOutbox.takeBatch(applicationContext, 50)
                if (batch.isEmpty()) break
                val confirmed = CallReportTopicSyncClient.sync(
                    config,
                    batch.map { it.toSyncEvent(applicationContext) },
                )
                val expected = batch.mapTo(hashSetOf()) { it.clientEventId }
                if (!confirmed.containsAll(expected)) {
                    CompanyCallNoteOutbox.recordFailure(applicationContext, "Сървърът не потвърди всички промени.")
                    return@withContext Result.retry()
                }
                ServerRecordIndex.markConfirmed(applicationContext, confirmed)
                CompanyCallNoteOutbox.acknowledge(applicationContext, confirmed)
                CompanyCallNoteOutbox.clearFailure(applicationContext)
                applicationContext.sendBroadcast(
                    Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(applicationContext.packageName),
                )
            }
            Result.success()
        } catch (error: Throwable) {
            CompanyCallNoteOutbox.recordFailure(
                applicationContext,
                error.message?.takeIf(String::isNotBlank) ?: "Синхронизацията ще бъде повторена.",
            )
            Result.retry()
        }
    }
}
