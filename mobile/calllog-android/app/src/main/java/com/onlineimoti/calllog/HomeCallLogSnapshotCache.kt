package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable last-known local Call Log pages.
 *
 * Home can draw calls immediately from this snapshot and then reconcile the Android
 * providers in the background. Notes remain in [HomeNotesSnapshotCache] so the two
 * caches can be combined into one complete first frame.
 */
internal object HomeCallLogSnapshotCache {
    private const val PREFS = "relationship_manager_home_call_log_snapshot_v1"
    private const val KEY_PAGE_INDEX = "page_index"
    private const val KEY_PAGE_PREFIX = "page:"
    private const val MAX_PAGES = 8
    private val lock = Any()

    fun readPage(
        context: Context,
        pageIndex: Int,
        pageSize: Int,
    ): List<PhoneCallRecord> = synchronized(lock) {
        val key = pageKey(context, pageIndex, pageSize)
        val raw = preferences(context).getString(KEY_PAGE_PREFIX + key, "").orEmpty()
        if (raw.isBlank()) return@synchronized emptyList()
        val items = runCatching { JSONObject(raw).optJSONArray("calls") }.getOrNull()
            ?: return@synchronized emptyList()
        buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let(::callFromJson)?.let(::add)
            }
        }.sortedByDescending { it.startedAt }
    }

    fun storePage(
        context: Context,
        pageIndex: Int,
        pageSize: Int,
        calls: List<PhoneCallRecord>,
    ) {
        if (calls.isEmpty()) return
        synchronized(lock) {
            val prefs = preferences(context)
            val key = pageKey(context, pageIndex, pageSize)
            val now = System.currentTimeMillis()
            val index = readIndex(prefs.getString(KEY_PAGE_INDEX, "").orEmpty())
            index.put(key, now)
            val retained = index.keys().asSequence()
                .map { indexedKey -> indexedKey to index.optLong(indexedKey) }
                .sortedByDescending { it.second }
                .take(MAX_PAGES)
                .toList()
            val retainedKeys = retained.mapTo(linkedSetOf()) { it.first }
            val trimmedIndex = JSONObject().apply {
                retained.reversed().forEach { (indexedKey, savedAt) -> put(indexedKey, savedAt) }
            }
            val page = JSONObject().apply {
                put("saved_at_ms", now)
                put("calls", JSONArray().apply { calls.forEach { put(callJson(it)) } })
            }
            val editor = prefs.edit()
                .putString(KEY_PAGE_PREFIX + key, page.toString())
                .putString(KEY_PAGE_INDEX, trimmedIndex.toString())
            index.keys().asSequence()
                .filter { it !in retainedKeys }
                .forEach { editor.remove(KEY_PAGE_PREFIX + it) }
            editor.apply()
        }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readIndex(raw: String): JSONObject {
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun pageKey(context: Context, pageIndex: Int, pageSize: Int): String {
        val mode = if (PageLoadingModeStore.usesPrefetch(context.applicationContext)) "grouped" else "fixed"
        return "$mode:${pageIndex.coerceAtLeast(0)}:${pageSize.coerceIn(5, 100)}"
    }

    private fun callJson(call: PhoneCallRecord): JSONObject = JSONObject().apply {
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

    private fun callFromJson(json: JSONObject): PhoneCallRecord? {
        val number = json.optString("number")
        val startedAt = json.optLong("started_at")
        if (number.isBlank() || startedAt <= 0L) return null
        return PhoneCallRecord(
            number = number,
            name = json.optString("name"),
            direction = json.optString("direction"),
            startedAt = startedAt,
            durationSeconds = json.optLong("duration"),
            smsBody = json.optString("sms_body"),
            providerId = json.optString("provider_id"),
            callType = json.optInt("call_type"),
            searchSnippet = json.optString("search_snippet"),
        )
    }
}
