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
    private const val MAX_PHONE_ENTRIES = 1_000
    private const val MAX_CALL_ENTRIES = 2_000

    fun mergeCached(context: Context, data: HomeRenderData): HomeRenderData {
        if (data.calls.isEmpty()) return data
        val snapshot = read(context) ?: return data
        val phoneKeys = data.calls
            .mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.number) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        val callKeys = data.calls.mapTo(linkedSetOf(), HomeCallNotesResolver::keyFor)

        return data.copy(
            contactNotesByNumber = linkedMapOf<String, String>().apply {
                snapshot.contactNotesByNumber.forEach { (key, value) ->
                    if (key in phoneKeys && value.isNotBlank()) put(key, value)
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
                    if (key in callKeys) put(key, value)
                }
                data.callNotesByCall.forEach(::put)
            },
        )
    }

    /** Replaces the current page in the cache while keeping other recently viewed pages. */
    fun store(context: Context, data: HomeRenderData) {
        if (data.calls.isEmpty()) return
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
                contactNotesByNumber = contactNotes.entries.takeLast(MAX_PHONE_ENTRIES)
                    .associateTo(linkedMapOf()) { it.key to it.value },
                contactNamesByNumber = contactNames.entries.takeLast(MAX_PHONE_ENTRIES)
                    .associateTo(linkedMapOf()) { it.key to it.value },
                callNotesByCall = callNotes.entries
                    .sortedByDescending { it.value.updatedAtMs }
                    .take(MAX_CALL_ENTRIES)
                    .associateTo(linkedMapOf()) { it.key to it.value },
            ),
        )
    }

    private fun read(context: Context): Snapshot? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    private fun write(context: Context, snapshot: Snapshot) {
        val root = JSONObject().apply {
            put("account", accountKey(context))
            put("saved_at_ms", System.currentTimeMillis())
            put("contact_notes", jsonStringMap(snapshot.contactNotesByNumber))
            put("contact_names", jsonStringMap(snapshot.contactNamesByNumber))
            put("call_notes", JSONObject().apply {
                snapshot.callNotesByCall.forEach { (key, note) -> put(key, noteJson(note)) }
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, root.toString())
            .apply()
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