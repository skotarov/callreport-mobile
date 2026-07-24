package com.onlineimoti.calllog

import org.json.JSONArray
import org.json.JSONObject

/** One yellow Home note lane entry. */
internal data class HomeGeneralNoteEntry(
    val text: String,
    val fromServer: Boolean,
)

/**
 * Keeps the existing single-string Home model backward compatible while allowing
 * the local main note and the unscoped server main note to be visible together.
 */
internal object HomeGeneralNoteBundle {
    private const val BUNDLE_PREFIX = "\u2063RM_GENERAL_NOTES_V1:"

    fun entries(value: String?): List<HomeGeneralNoteEntry> {
        val raw = value.orEmpty()
        if (raw.isBlank()) return emptyList()
        if (!raw.startsWith(BUNDLE_PREFIX)) return listOf(legacyEntry(raw))
        return runCatching {
            val array = JSONArray(raw.removePrefix(BUNDLE_PREFIX))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text").trim()
                    if (text.isBlank()) continue
                    add(HomeGeneralNoteEntry(text, item.optBoolean("server", false)))
                }
            }
        }.getOrElse { listOf(legacyEntry(raw)) }
    }

    fun replaceServer(existing: String?, serverValue: String?): String {
        val local = entries(existing).filterNot { it.fromServer }
        val server = entries(serverValue).filter { it.fromServer }
        return encode(local + server)
    }

    fun withoutServer(value: String?): String = encode(entries(value).filterNot { it.fromServer })

    private fun encode(values: List<HomeGeneralNoteEntry>): String {
        val unique = linkedMapOf<String, HomeGeneralNoteEntry>()
        values.forEach { entry ->
            val text = entry.text.trim()
            if (text.isBlank()) return@forEach
            val key = normalize(text)
            val current = unique[key]
            if (current == null || current.fromServer && !entry.fromServer) {
                unique[key] = HomeGeneralNoteEntry(text, entry.fromServer)
            }
        }
        val entries = unique.values.toList()
        return when (entries.size) {
            0 -> ""
            1 -> entries.first().let { entry ->
                ServerNoteVisuals.prefixedIfServer(entry.text, entry.fromServer)
            }
            else -> BUNDLE_PREFIX + JSONArray().apply {
                entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("text", entry.text)
                        put("server", entry.fromServer)
                    })
                }
            }
        }
    }

    private fun legacyEntry(value: String): HomeGeneralNoteEntry = HomeGeneralNoteEntry(
        text = ServerNoteVisuals.withoutPrefix(value),
        fromServer = ServerNoteVisuals.isPrefixed(value),
    )

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()
}
