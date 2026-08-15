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
import java.util.concurrent.TimeUnit

/** Durable queue for a note or SMS explicitly classified under one company topic. */
internal object CallReportTopicNoteOutbox {
    private const val PREFS = "callreport_topic_note_outbox"
    private const val KEY_OPERATIONS = "operations_v1"
    private const val KEY_LAST_FAILURE = "last_failure"
    private const val UNIQUE_WORK = "callreport_topic_note_sync"
    private val lock = Any()

    fun enqueueGeneral(context: Context, phone: String, note: String, companyId: String, scheduleWorker: Boolean = true): Boolean {
        val appContext = context.applicationContext
        val key = phoneKey(phone)
        val target = companyId.trim()
        if (!hasServerCompanyScope(appContext, phone) || key.isBlank() || target.isBlank()) return false
        val now = System.currentTimeMillis()
        return enqueue(appContext, CallReportQueuedTopicNote(
            clientEventId = "${CallReportInstallationId.get(appContext)}:topic:general:$key:$target",
            companyId = target,
            phone = phone,
            direction = "",
            occurredAtMs = now,
            durationSeconds = 0L,
            note = note.trim(),
            contactName = contactName(appContext, phone),
            updatedAtMs = now,
        ), scheduleWorker)
    }

    fun enqueueCall(
        context: Context,
        phone: String,
        note: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        companyId: String,
        clientNoteId: String = "",
        scheduleWorker: Boolean = true,
    ): Boolean {
        val appContext = context.applicationContext
        val target = companyId.trim()
        if (!hasServerCompanyScope(appContext, phone) || target.isBlank() || phoneKey(phone).isBlank() || callAt <= 0L) return false
        val stableId = clientNoteId.ifBlank { LocalNotesFileStore.clientNoteIdForCall(phone, callAt, direction) }
        if (stableId.isBlank()) return false
        return enqueue(appContext, CallReportQueuedTopicNote(
            clientEventId = ServerRecordIndex.callNoteEventId(appContext, stableId),
            companyId = target,
            phone = phone,
            direction = direction,
            occurredAtMs = callAt,
            durationSeconds = durationSeconds.coerceAtLeast(0L),
            note = note.trim(),
            contactName = contactName(appContext, phone),
            updatedAtMs = System.currentTimeMillis(),
        ), scheduleWorker)
    }

    fun enqueueUnassignCall(
        context: Context,
        phone: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        clientNoteId: String = "",
    ): Boolean {
        val appContext = context.applicationContext
        if (!hasServerCompanyScope(appContext, phone) || phoneKey(phone).isBlank() || callAt <= 0L) return false
        val stableId = clientNoteId.ifBlank { LocalNotesFileStore.clientNoteIdForCall(phone, callAt, direction) }
        if (stableId.isBlank()) return false
        return enqueue(appContext, CallReportQueuedTopicNote(
            clientEventId = ServerRecordIndex.callNoteEventId(appContext, stableId),
            companyId = "",
            phone = phone,
            direction = direction,
            occurredAtMs = callAt,
            durationSeconds = durationSeconds.coerceAtLeast(0L),
            note = "",
            contactName = contactName(appContext, phone),
            updatedAtMs = System.currentTimeMillis(),
            clearCompanyAssignment = true,
        ), true)
    }

    fun enqueueSms(context: Context, phone: String, sms: SmsMessageRecord, companyId: String): Boolean {
        val appContext = context.applicationContext
        val target = companyId.trim()
        val providerId = sms.providerId.trim()
        if (!hasServerCompanyScope(appContext, phone) || target.isBlank() || providerId.isBlank() || phoneKey(phone).isBlank() || sms.timestampMs <= 0L) return false
        return enqueue(appContext, CallReportQueuedTopicNote(
            clientEventId = ServerRecordIndex.communicationEventId(appContext, "sms", providerId),
            companyId = target,
            phone = phone,
            direction = if (sms.isOutgoing) "out" else "in",
            occurredAtMs = sms.timestampMs,
            durationSeconds = 0L,
            note = sms.body.trim(),
            contactName = contactName(appContext, phone),
            updatedAtMs = System.currentTimeMillis(),
            communicationType = "sms",
        ), true)
    }

    fun pendingCount(context: Context): Int = synchronized(lock) { readLocked(context).size }
    fun pendingPhoneKeys(context: Context): Set<String> = synchronized(lock) { readLocked(context).mapTo(linkedSetOf()) { phoneKey(it.phone) }.filterTo(linkedSetOf()) { it.isNotBlank() } }
    fun hasPendingForPhone(context: Context, phone: String): Boolean {
        val key = phoneKey(phone)
        if (key.isBlank()) return false
        return synchronized(lock) { readLocked(context).any { phoneKey(it.phone) == key } }
    }
    fun isCallPending(context: Context, phone: String, direction: String, callAt: Long): Boolean {
        val clientNoteId = LocalNotesFileStore.clientNoteIdForCall(phone, callAt, direction)
        if (clientNoteId.isBlank()) return false
        val eventId = ServerRecordIndex.callNoteEventId(context, clientNoteId)
        return synchronized(lock) { readLocked(context).any { it.clientEventId == eventId } }
    }
    fun isGeneralPending(context: Context, phone: String, companyId: String): Boolean {
        val key = phoneKey(phone)
        val target = companyId.trim()
        if (key.isBlank() || target.isBlank()) return false
        val eventId = "${CallReportInstallationId.get(context.applicationContext)}:topic:general:$key:$target"
        return synchronized(lock) { readLocked(context).any { it.clientEventId == eventId } }
    }

    fun lastFailure(context: Context): String = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_FAILURE, "").orEmpty().trim()
    internal fun recordFailure(context: Context, message: String) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_FAILURE, message.trim()).commit() }
    internal fun clearFailure(context: Context) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_FAILURE).commit() }

    fun requestSyncNow(context: Context) {
        if (!hasPending(context)) return
        val request = OneTimeWorkRequestBuilder<CallReportTopicNoteWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    internal fun takeBatch(context: Context, limit: Int): List<CallReportQueuedTopicNote> = synchronized(lock) {
        val appContext = context.applicationContext
        val operations = readLocked(appContext)
        val eligibleOperations = operations.filter { hasServerCompanyScope(appContext, it.phone) }
        if (eligibleOperations.size != operations.size) writeLocked(appContext, eligibleOperations)
        eligibleOperations.sortedBy { it.updatedAtMs }.take(limit.coerceIn(1, 50))
    }
    internal fun acknowledge(context: Context, clientEventIds: Collection<String>) {
        if (clientEventIds.isEmpty()) return
        val ids = clientEventIds.toSet()
        synchronized(lock) { writeLocked(context, readLocked(context).filterNot { it.clientEventId in ids }) }
    }
    fun hasPending(context: Context): Boolean = synchronized(lock) { readLocked(context).isNotEmpty() }

    private fun enqueue(context: Context, operation: CallReportQueuedTopicNote, scheduleWorker: Boolean): Boolean {
        ServerRecordIndex.markPending(context, operation.clientEventId)
        synchronized(lock) {
            val operations = readLocked(context).filterNot { it.clientEventId == operation.clientEventId }.toMutableList()
            operations += operation
            writeLocked(context, operations)
        }
        if (scheduleWorker) enqueueWorker(context)
        return true
    }

    private fun enqueueWorker(context: Context) {
        val request = OneTimeWorkRequestBuilder<CallReportTopicNoteWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private fun readLocked(context: Context): List<CallReportQueuedTopicNote> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OPERATIONS, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList { for (index in 0 until array.length()) array.optJSONObject(index)?.toOperation()?.let(::add) }
    }
    private fun writeLocked(context: Context, operations: List<CallReportQueuedTopicNote>) {
        val payload = JSONArray().apply { operations.forEach { put(it.toJson()) } }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OPERATIONS, payload.toString()).commit()
    }

    private fun CallReportQueuedTopicNote.toJson(): JSONObject = JSONObject().apply {
        put("client_event_id", clientEventId); put("company_id", companyId); put("phone", phone); put("direction", direction); put("occurred_at_ms", occurredAtMs); put("duration_seconds", durationSeconds); put("note", note); put("contact_name", contactName); put("updated_at_ms", updatedAtMs); put("communication_type", communicationType); put("clear_company_assignment", clearCompanyAssignment)
    }
    private fun JSONObject.toOperation(): CallReportQueuedTopicNote? {
        val id = optString("client_event_id").trim(); val companyId = optString("company_id").trim(); val phone = optString("phone").trim(); val clearCompanyAssignment = optBoolean("clear_company_assignment", false)
        if (id.isBlank() || phoneKey(phone).isBlank() || (!clearCompanyAssignment && companyId.isBlank())) return null
        return CallReportQueuedTopicNote(id, companyId, phone, optString("direction").trim(), optLong("occurred_at_ms", 0L).takeIf { it > 0L } ?: System.currentTimeMillis(), optLong("duration_seconds", 0L).coerceAtLeast(0L), optString("note"), optString("contact_name").trim(), optLong("updated_at_ms", 0L).takeIf { it > 0L } ?: System.currentTimeMillis(), optString("communication_type", "note").trim().ifBlank { "note" }, clearCompanyAssignment)
    }
    private fun hasServerCompanyScope(context: Context, phone: String): Boolean = ContactServerCompanyScope.isAvailable(context.applicationContext, phone)
    private fun contactName(context: Context, phone: String): String = ContactGroupFilter.resolveDisplayName(context, phone).orEmpty().trim()
    private fun phoneKey(phone: String): String { val digits = phone.filter { it.isDigit() }; return if (digits.length > 9) digits.takeLast(9) else digits }
}
