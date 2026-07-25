package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent, coalescing outbox for locally edited notes. */
internal data class CallReportQueuedNote(
    val clientEventId: String,
    val phone: String,
    val direction: String,
    val occurredAtMs: Long,
    val durationSeconds: Long,
    val note: String,
    val contactName: String,
    val updatedAtMs: Long,
    /** Uses an existing cloud client_event_id instead of inserting a second note. */
    val editExistingServerNote: Boolean = false,
) {
    val isGeneralNote: Boolean
        get() = clientEventId.contains(":note:general:")

    fun toSyncEvent(context: Context): CallReportSyncEvent = CallReportSyncEvent(
        clientEventId = clientEventId,
        communicationType = "note",
        direction = direction,
        status = "",
        phone = phone,
        contactName = contactName,
        occurredAtMs = occurredAtMs,
        durationSeconds = durationSeconds.coerceAtLeast(0L),
        providerRowId = clientEventId,
        deviceId = CallReportInstallationId.get(context),
        appVersion = BuildConfig.VERSION_NAME,
        note = note,
        updatedAtMs = updatedAtMs,
        editExistingNote = editExistingServerNote,
    )

    fun toHistoryEvent(): CallReportHistoryEvent = CallReportHistoryEvent(
        clientEventId = clientEventId,
        communicationType = "note",
        phone = phone,
        direction = direction,
        occurredAtMs = occurredAtMs,
        durationSeconds = durationSeconds.coerceAtLeast(0L),
        note = note,
        contactName = contactName,
        createdAtMs = updatedAtMs,
        updatedAtMs = updatedAtMs,
    )
}

internal object CallReportNoteOutbox {
    private const val PREFS = "callreport_note_outbox"
    private const val KEY_OPERATIONS = "operations_v1"
    private const val KEY_LAST_FAILURE = "last_failure"
    private val lock = Any()

    /**
     * A deliberately saved main note is server data in its own right. It is queued even
     * while the server settings are incomplete, so the visible reason is retained and
     * the note can be retried automatically after the settings are fixed.
     */
    fun enqueueGeneral(context: Context, phone: String, note: String): Boolean {
        val appContext = context.applicationContext
        val key = phoneKey(phone)
        if (key.isBlank()) return false
        val now = System.currentTimeMillis()
        return enqueue(appContext, CallReportQueuedNote(
            clientEventId = "${CallReportInstallationId.get(appContext)}:note:general:$key",
            phone = phone,
            direction = "",
            occurredAtMs = now,
            durationSeconds = 0L,
            note = note.trim(),
            contactName = contactName(appContext, phone),
            updatedAtMs = now,
        ))
    }

    fun enqueueCall(
        context: Context,
        phone: String,
        note: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        clientNoteId: String = "",
    ): Boolean {
        val appContext = context.applicationContext
        if (!CrmContactSyncStore.isEnabled(appContext, phone) || phoneKey(phone).isBlank() || callAt <= 0L) return false
        val stableId = clientNoteId.ifBlank { LocalNotesFileStore.clientNoteIdForCall(phone, callAt, direction) }
        if (stableId.isBlank()) return false
        return enqueue(appContext, CallReportQueuedNote(
            clientEventId = "${CallReportInstallationId.get(appContext)}:note:call:$stableId",
            phone = phone,
            direction = direction,
            occurredAtMs = callAt,
            durationSeconds = durationSeconds.coerceAtLeast(0L),
            note = note.trim(),
            contactName = contactName(appContext, phone),
            updatedAtMs = System.currentTimeMillis(),
        ))
    }

    /** Queues an edit/delete against the original server note without requiring a local CRM marker. */
    fun enqueueExistingServerNote(
        context: Context,
        phone: String,
        note: String,
        serverClientEventId: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
    ): Boolean {
        val appContext = context.applicationContext
        val clientEventId = serverClientEventId.trim()
        if (phoneKey(phone).isBlank() || clientEventId.isBlank()) return false
        val now = System.currentTimeMillis()
        return enqueue(appContext, CallReportQueuedNote(
            clientEventId = clientEventId,
            phone = phone,
            direction = direction,
            occurredAtMs = callAt.takeIf { it > 0L } ?: now,
            durationSeconds = durationSeconds.coerceAtLeast(0L),
            note = note.trim(),
            contactName = contactName(appContext, phone),
            updatedAtMs = now,
            editExistingServerNote = true,
        ))
    }

    /**
     * Pending edits/deletes of server-only notes must override an older History response
     * on Home. In particular, a blank note is a tombstone until the server confirms it.
     */
    fun pendingExistingServerEvents(
        context: Context,
        phones: Collection<String>,
    ): List<CallReportHistoryEvent> {
        val keys = phones.mapTo(hashSetOf(), ::phoneKey).filterTo(hashSetOf()) { it.isNotBlank() }
        if (keys.isEmpty()) return emptyList()
        return synchronized(lock) {
            readLocked(context.applicationContext)
                .filter { operation ->
                    operation.editExistingServerNote && phoneKey(operation.phone) in keys
                }
                .map(CallReportQueuedNote::toHistoryEvent)
        }
    }

    fun isCallPending(context: Context, phone: String, note: ContactCallNote): Boolean {
        val localId = note.clientNoteId.ifBlank {
            LocalNotesFileStore.clientNoteIdForCall(phone, note.callAt, note.direction)
        }
        return localId.isNotBlank() && isPending(context, ServerRecordIndex.callNoteEventId(context, localId))
    }

    fun isGeneralPending(context: Context, phone: String): Boolean {
        return isPending(context, ServerRecordIndex.generalNoteEventId(context, phone))
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

    fun enqueueCurrentLocalNotes(context: Context, phone: String) {
        val appContext = context.applicationContext
        enqueueGeneral(appContext, phone, ContactNoteReader.generalNoteForPhone(appContext, phone))
        if (!CrmContactSyncStore.isEnabled(appContext, phone)) return
        ContactNoteReader.callNotesForPhone(appContext, phone).forEach { callNote ->
            if (callNote.callAt > 0L) enqueueCall(
                context = appContext,
                phone = phone,
                note = callNote.note,
                direction = callNote.direction,
                callAt = callNote.callAt,
                durationSeconds = callNote.durationSeconds,
                clientNoteId = callNote.clientNoteId,
            )
        }
    }

    fun removeForPhone(context: Context, phone: String) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        synchronized(lock) { writeLocked(context, readLocked(context).filterNot { phoneKey(it.phone) == key }) }
    }

    internal fun takeBatch(context: Context, limit: Int): List<CallReportQueuedNote> = synchronized(lock) {
        val all = readLocked(context)
        val eligible = all.filter { operation ->
            operation.isGeneralNote || operation.editExistingServerNote || CrmContactSyncStore.isEnabled(context.applicationContext, operation.phone)
        }
        if (eligible.size != all.size) writeLocked(context, eligible)
        eligible.sortedBy { it.updatedAtMs }.take(limit.coerceIn(1, 50))
    }

    internal fun acknowledge(context: Context, clientEventIds: Collection<String>) {
        if (clientEventIds.isEmpty()) return
        val ids = clientEventIds.toSet()
        synchronized(lock) { writeLocked(context, readLocked(context).filterNot { it.clientEventId in ids }) }
    }

    fun hasPending(context: Context): Boolean = synchronized(lock) { readLocked(context).isNotEmpty() }

    private fun isPending(context: Context, clientEventId: String): Boolean = synchronized(lock) {
        readLocked(context).any { it.clientEventId == clientEventId }
    }

    private fun enqueue(context: Context, operation: CallReportQueuedNote): Boolean {
        ServerRecordIndex.markPending(context, operation.clientEventId)
        synchronized(lock) {
            val operations = readLocked(context).filterNot { it.clientEventId == operation.clientEventId }.toMutableList()
            operations += operation
            writeLocked(context, operations)
        }
        CallReportNoteOutboxScheduler.enqueue(context, reason = "note_changed")
        return true
    }

    private fun readLocked(context: Context): List<CallReportQueuedNote> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OPERATIONS, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toOperation()?.let(::add)
            }
        }
    }

    private fun writeLocked(context: Context, operations: List<CallReportQueuedNote>) {
        val payload = JSONArray().apply { operations.forEach { put(it.toJson()) } }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_OPERATIONS, payload.toString()).commit()
    }

    private fun CallReportQueuedNote.toJson(): JSONObject = JSONObject().apply {
        put("client_event_id", clientEventId)
        put("phone", phone)
        put("direction", direction)
        put("occurred_at_ms", occurredAtMs)
        put("duration_seconds", durationSeconds)
        put("note", note)
        put("contact_name", contactName)
        put("updated_at_ms", updatedAtMs)
        put("edit_existing_server_note", editExistingServerNote)
    }

    private fun JSONObject.toOperation(): CallReportQueuedNote? {
        val id = optString("client_event_id").trim()
        val phone = optString("phone").trim()
        if (id.isBlank() || phoneKey(phone).isBlank()) return null
        return CallReportQueuedNote(
            clientEventId = id,
            phone = phone,
            direction = optString("direction").trim(),
            occurredAtMs = optLong("occurred_at_ms", 0L).takeIf { it > 0L } ?: System.currentTimeMillis(),
            durationSeconds = optLong("duration_seconds", 0L).coerceAtLeast(0L),
            note = optString("note"),
            contactName = optString("contact_name").trim(),
            updatedAtMs = optLong("updated_at_ms", 0L).takeIf { it > 0L } ?: System.currentTimeMillis(),
            editExistingServerNote = optBoolean("edit_existing_server_note", false),
        )
    }

    private fun contactName(context: Context, phone: String): String =
        ContactGroupFilter.resolveDisplayName(context, phone).orEmpty().trim()

    private fun phoneKey(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
