package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable last-known Home note state.
 *
 * Calls stay the source of truth for the visible timeline. This cache only lets the
 * yellow and blue note content join the first render while the local providers and
 * server are refreshed in the background.
 */
internal object HomeNotesSnapshotCache {
    private const val PREFS = "relationship_manager_home_notes_snapshot_v1"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val KEY_MUTATION_REVISION = "mutation_revision"
    private const val MAX_PHONE_ENTRIES = 1_000
    private const val MAX_CALL_ENTRIES = 2_000
    private val lock = Any()

    fun mergeCached(context: Context, data: HomeRenderData): HomeRenderData {
        if (data.calls.isEmpty()) return data
        val snapshot = synchronized(lock) { read(context) } ?: return data
        val remoteReady = CallReportRemoteAccess.isReady(ConfigStore.load(context.applicationContext))
        val phoneKeys = data.calls
            .mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.number) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        val callKeys = data.calls.mapTo(linkedSetOf(), HomeCallNotesResolver::keyFor)

        return data.copy(
            contactNotesByNumber = linkedMapOf<String, String>().apply {
                snapshot.contactNotesByNumber.forEach { (key, value) ->
                    if (key !in phoneKeys) return@forEach
                    val visibleValue = if (remoteReady) value else HomeGeneralNoteBundle.withoutServer(value)
                    if (visibleValue.isNotBlank()) put(key, visibleValue)
                }
                data.contactNotesByNumber.forEach(::put)
            },
            contactNamesByNumber = linkedMapOf<String, String>().apply {
                snapshot.contactNamesByNumber.forEach { (key, value) ->
                    if (key in phoneKeys && value.isNotBlank()) put(key, value)
                }
                data.contactNamesByNumber.forEach(::put)
            },
            callNotesByCall = linkedMapOf<String, HomeCallNote>().apply {
                snapshot.callNotesByCall.forEach { (key, value) ->
                    if (key !in callKeys) return@forEach
                    visibleCallNote(value, remoteReady)?.let { put(key, it) }
                }
                data.callNotesByCall.forEach(::put)
            },
        )
    }

    /** Captured before an async Home load so a later deletion can invalidate its write. */
    fun mutationRevision(context: Context): Long = synchronized(lock) {
        mutationRevisionLocked(context)
    }

    /** Replaces the current page in the cache while keeping other recently viewed pages. */
    fun store(context: Context, data: HomeRenderData) {
        if (data.calls.isEmpty()) return
        synchronized(lock) { storeLocked(context, data) }
    }

    /**
     * Writes only when no note deletion happened since the caller began loading.
     * The revision check and snapshot write share one lock, so deletion cannot slip
     * between them and an old async response cannot resurrect a removed note.
     */
    fun storeIfRevision(context: Context, data: HomeRenderData, expectedRevision: Long): Boolean {
        if (data.calls.isEmpty()) return mutationRevision(context) == expectedRevision
        return synchronized(lock) {
            if (mutationRevisionLocked(context) != expectedRevision) return@synchronized false
            storeLocked(context, data)
            true
        }
    }

    private fun storeLocked(context: Context, data: HomeRenderData) {
        val current = read(context) ?: Snapshot()
        val phoneKeys = data.calls
            .mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.number) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        val callKeys = data.calls.mapTo(linkedSetOf(), HomeCallNotesResolver::keyFor)

        val contactNotes = current.contactNotesByNumber.toMutableMap().apply {
            phoneKeys.forEach(::remove)
            data.contactNotesByNumber.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) put(key, value)
            }
        }
        val contactNames = current.contactNamesByNumber.toMutableMap().apply {
            phoneKeys.forEach(::remove)
            data.contactNamesByNumber.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) put(key, value)
            }
        }
        val callNotes = current.callNotesByCall.toMutableMap().apply {
            callKeys.forEach(::remove)
            data.callNotesByCall.forEach { (key, value) ->
                if (key.isNotBlank() && value.text.isNotBlank()) put(key, value)
            }
        }

        write(
            context,
            Snapshot(
                contactNotesByNumber = contactNotes.entries.toList().takeLast(MAX_PHONE_ENTRIES)
                    .associateTo(linkedMapOf()) { it.key to it.value },
                contactNamesByNumber = contactNames.entries.toList().takeLast(MAX_PHONE_ENTRIES)
                    .associateTo(linkedMapOf()) { it.key to it.value },
                callNotesByCall = callNotes.entries
                    .sortedByDescending { it.value.updatedAtMs }
                    .take(MAX_CALL_ENTRIES)
                    .associateTo(linkedMapOf()) { it.key to it.value },
            ),
        )
    }

    /**
     * A deletion is represented by absence, so it cannot override an older cached value.
     * Remove that value synchronously before Home resumes. For a blue note, preserve the
     * other independent Local/company notes attached to the same call whenever its id is known.
     */
    fun invalidateDeletedNote(
        context: Context,
        phone: String,
        isGeneralNote: Boolean,
        callAtMs: Long = 0L,
        direction: String = "",
        serverClientEventId: String = "",
    ) {
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        if (phoneKey.isBlank()) return
        synchronized(lock) {
            val nextRevision = maxOf(
                mutationRevisionLocked(context) + 1L,
                System.currentTimeMillis(),
            )
            val current = read(context)
            if (current == null) {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_MUTATION_REVISION, nextRevision)
                    .commit()
                return@synchronized
            }
            val updated = if (isGeneralNote) {
                current.copy(contactNotesByNumber = current.contactNotesByNumber - phoneKey)
            } else {
                current.copy(
                    callNotesByCall = removeDeletedCallNote(
                        notes = current.callNotesByCall,
                        phoneKey = phoneKey,
                        callAtMs = callAtMs,
                        direction = direction,
                        serverClientEventId = serverClientEventId,
                    ),
                )
            }
            write(
                context = context,
                snapshot = updated,
                synchronous = true,
                mutationRevision = nextRevision,
            )
        }
    }

    internal fun removeDeletedCallNote(
        notes: Map<String, HomeCallNote>,
        phoneKey: String,
        callAtMs: Long,
        direction: String,
        serverClientEventId: String,
    ): Map<String, HomeCallNote> {
        if (phoneKey.isBlank() || notes.isEmpty()) return notes
        val targetId = serverClientEventId.trim()
        val cleanDirection = direction.trim()
        val result = notes.toMutableMap()
        val candidateKeys = notes.keys.filter { key ->
            val parts = key.split('|', limit = 3)
            if (parts.size < 3 || parts[0] != phoneKey) return@filter false
            val keyCallAt = parts[1].toLongOrNull() ?: return@filter false
            val keyDirection = parts[2].trim()
            (callAtMs <= 0L || keyCallAt == callAtMs) &&
                (cleanDirection.isBlank() || keyDirection.isBlank() || keyDirection == cleanDirection)
        }

        candidateKeys.forEach { key ->
            val current = result[key] ?: return@forEach
            val expanded = current.expandedNotes()
            val remaining = if (targetId.isBlank()) {
                emptyList()
            } else {
                expanded.filterNot { note -> note.serverClientEventId.trim() == targetId }
            }
            when {
                remaining.size == expanded.size -> {
                    // Older snapshots may predate stored event ids. Clearing the concrete
                    // call is safer than resurrecting the note the user just deleted.
                    result.remove(key)
                }
                remaining.isEmpty() -> result.remove(key)
                else -> result[key] = remaining.first().copy(relatedNotes = remaining.drop(1))
            }
        }
        return result
    }

    private fun visibleCallNote(note: HomeCallNote, remoteReady: Boolean): HomeCallNote? {
        if (remoteReady) return note
        val localNotes = note.expandedNotes().filterNot { it.fromServer }
        if (localNotes.isEmpty()) return null
        return localNotes.first().copy(relatedNotes = localNotes.drop(1))
    }

    private fun read(context: Context): Snapshot? {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, "")
            .orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.optString("account") != accountKey(context)) return@runCatching null
            Snapshot(
                contactNotesByNumber = stringMap(root.optJSONObject("contact_notes")),
                contactNamesByNumber = stringMap(root.optJSONObject("contact_names")),
                callNotesByCall = callNoteMap(root.optJSONObject("call_notes")),
            )
        }.getOrNull()
    }

    private fun mutationRevisionLocked(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(KEY_MUTATION_REVISION, 0L)

    private fun write(
        context: Context,
        snapshot: Snapshot,
        synchronous: Boolean = false,
        mutationRevision: Long? = null,
    ) {
        val root = JSONObject().apply {
            put("account", accountKey(context))
            put("saved_at_ms", System.currentTimeMillis())
            put("contact_notes", jsonStringMap(snapshot.contactNotesByNumber))
            put("contact_names", jsonStringMap(snapshot.contactNamesByNumber))
            put("call_notes", JSONObject().apply {
                snapshot.callNotesByCall.forEach { (key, note) -> put(key, noteJson(note)) }
            })
        }
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, root.toString())
        mutationRevision?.let { edit.putLong(KEY_MUTATION_REVISION, it) }
        if (synchronous) edit.commit() else edit.apply()
    }

    private fun accountKey(context: Context): String {
        val config = ConfigStore.load(context.applicationContext)
        return "${config.baseUrl.trim()}#${config.accessToken.trim()}".hashCode().toString()
    }

    private fun jsonStringMap(values: Map<String, String>) = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
    }

    private fun stringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        return linkedMapOf<String, String>().apply {
            json.keys().forEach { key ->
                json.optString(key).trim().takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
    }

    private fun callNoteMap(json: JSONObject?): Map<String, HomeCallNote> {
        if (json == null) return emptyMap()
        return linkedMapOf<String, HomeCallNote>().apply {
            json.keys().forEach { key ->
                json.optJSONObject(key)?.let(::homeCallNote)?.takeIf { it.text.isNotBlank() }?.let { put(key, it) }
            }
        }
    }

    private fun noteJson(note: HomeCallNote): JSONObject = JSONObject().apply {
        put("text", note.text)
        put("updated_at_ms", note.updatedAtMs)
        put("from_server", note.fromServer)
        put("author_name", note.authorName)
        put("company_id", note.companyId)
        put("server_client_event_id", note.serverClientEventId)
        put("editable", note.editable)
        put("related", JSONArray().apply { note.relatedNotes.forEach { put(noteJson(it)) } })
    }

    private fun homeCallNote(json: JSONObject): HomeCallNote = HomeCallNote(
        text = json.optString("text").trim(),
        updatedAtMs = json.optLong("updated_at_ms"),
        fromServer = json.optBoolean("from_server"),
        authorName = json.optString("author_name").trim(),
        companyId = json.optString("company_id").trim(),
        serverClientEventId = json.optString("server_client_event_id").trim(),
        editable = json.optBoolean("editable", true),
        relatedNotes = buildList {
            val items = json.optJSONArray("related") ?: return@buildList
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { add(homeCallNote(it)) }
            }
        },
    )

    private data class Snapshot(
        val contactNotesByNumber: Map<String, String> = emptyMap(),
        val contactNamesByNumber: Map<String, String> = emptyMap(),
        val callNotesByCall: Map<String, HomeCallNote> = emptyMap(),
    )
}
