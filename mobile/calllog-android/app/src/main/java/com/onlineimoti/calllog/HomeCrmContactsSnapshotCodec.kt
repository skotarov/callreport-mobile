package com.onlineimoti.calllog

import org.json.JSONArray
import org.json.JSONObject

/** JSON codec for the small, already paged Clients snapshot kept on the device. */
internal object HomeCrmContactsSnapshotCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_RELATED_NOTE_DEPTH = 2
    private const val MAX_RELATED_NOTES = 20

    fun encode(data: HomeRenderData): String = JSONObject().apply {
        put("schema", SCHEMA_VERSION)
        put("calls", JSONArray().apply { data.calls.forEach { put(callToJson(it)) } })
        put("contact_notes", stringMapToJson(data.contactNotesByNumber))
        put("contact_names", stringMapToJson(data.contactNamesByNumber))
        put("call_notes", callNotesToJson(data.callNotesByCall))
    }.toString()

    fun decode(raw: String): HomeRenderData? = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("schema", 0) != SCHEMA_VERSION) return@runCatching null
        val calls = buildList {
            val items = root.optJSONArray("calls") ?: JSONArray()
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let(::callFromJson)?.let(::add)
            }
        }
        HomeRenderData(
            calls = calls,
            contactNotesByNumber = stringMapFromJson(root.optJSONObject("contact_notes")),
            contactNamesByNumber = stringMapFromJson(root.optJSONObject("contact_names")),
            callNotesByCall = callNotesFromJson(root.optJSONObject("call_notes")),
        )
    }.getOrNull()

    private fun callToJson(call: PhoneCallRecord): JSONObject = JSONObject().apply {
        put("number", call.number)
        put("name", call.name)
        put("direction", call.direction)
        put("started_at", call.startedAt)
        put("duration", call.durationSeconds)
        put("sms_body", call.smsBody)
        put("provider_id", call.providerId)
        put("call_type", call.callType)
        put("search_snippet", call.searchSnippet)
    }

    private fun callFromJson(value: JSONObject): PhoneCallRecord? {
        val number = value.optString("number").trim()
        if (number.isBlank()) return null
        return PhoneCallRecord(
            number = number,
            name = value.optString("name"),
            direction = value.optString("direction"),
            startedAt = value.optLong("started_at", 0L),
            durationSeconds = value.optLong("duration", 0L),
            smsBody = value.optString("sms_body"),
            providerId = value.optString("provider_id"),
            callType = value.optInt("call_type", 0),
            searchSnippet = value.optString("search_snippet"),
        )
    }

    private fun stringMapToJson(values: Map<String, String>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) put(key, value)
        }
    }

    private fun stringMapFromJson(value: JSONObject?): Map<String, String> {
        if (value == null) return emptyMap()
        return buildMap {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val text = value.optString(key)
                if (key.isNotBlank() && text.isNotBlank()) put(key, text)
            }
        }
    }

    private fun callNotesToJson(values: Map<String, HomeCallNote>): JSONObject = JSONObject().apply {
        values.forEach { (key, note) ->
            if (key.isNotBlank() && note.text.isNotBlank()) put(key, noteToJson(note, depth = 0))
        }
    }

    private fun callNotesFromJson(value: JSONObject?): Map<String, HomeCallNote> {
        if (value == null) return emptyMap()
        return buildMap {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                value.optJSONObject(key)?.let { noteFromJson(it, depth = 0) }?.let { note ->
                    if (key.isNotBlank()) put(key, note)
                }
            }
        }
    }

    private fun noteToJson(note: HomeCallNote, depth: Int): JSONObject = JSONObject().apply {
        put("text", note.text)
        put("updated_at", note.updatedAtMs)
        put("from_server", note.fromServer)
        put("author", note.authorName)
        put("company_id", note.companyId)
        put("server_event_id", note.serverClientEventId)
        put("editable", note.editable)
        if (depth < MAX_RELATED_NOTE_DEPTH && note.relatedNotes.isNotEmpty()) {
            put("related", JSONArray().apply {
                note.relatedNotes.take(MAX_RELATED_NOTES).forEach { related ->
                    put(noteToJson(related, depth + 1))
                }
            })
        }
    }

    private fun noteFromJson(value: JSONObject, depth: Int): HomeCallNote? {
        val text = value.optString("text").trim()
        if (text.isBlank()) return null
        val related = if (depth < MAX_RELATED_NOTE_DEPTH) {
            buildList {
                val items = value.optJSONArray("related") ?: JSONArray()
                for (index in 0 until minOf(items.length(), MAX_RELATED_NOTES)) {
                    items.optJSONObject(index)?.let { noteFromJson(it, depth + 1) }?.let(::add)
                }
            }
        } else {
            emptyList()
        }
        return HomeCallNote(
            text = text,
            updatedAtMs = value.optLong("updated_at", 0L),
            fromServer = value.optBoolean("from_server", false),
            authorName = value.optString("author"),
            companyId = value.optString("company_id"),
            serverClientEventId = value.optString("server_event_id"),
            editable = value.optBoolean("editable", true),
            relatedNotes = related,
        )
    }
}
