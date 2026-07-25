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
    private const val KEY_PAGES = "pages"
    private const val MAX_PAGES = 8
    private val lock = Any()

    fun readPage(
        context: Context,
        pageIndex: Int,
        pageSize: Int,
    ): List<PhoneCallRecord> = synchronized(lock) {
        val pages = readPages(context)
        val page = pages.optJSONObject(pageKey(context, pageIndex, pageSize)) ?: return@synchronized emptyList()
        val items = page.optJSONArray("calls") ?: return@synchronized emptyList()
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
            val pages = readPages(context)
            val key = pageKey(context, pageIndex, pageSize)
            pages.put(
                key,
                JSONObject().apply {
                    put("saved_at_ms", System.currentTimeMillis())
                    put("calls", JSONArray().apply { calls.forEach { put(callJson(it)) } })
                },
            )
            val trimmed = pages.keys().asSequence()
                .mapNotNull { pageKey ->
                    pages.optJSONObject(pageKey)?.let { page ->
                        Triple(pageKey, page.optLong("saved_at_ms"), page)
                    }
                }
                .sortedByDescending { it.second }
                .take(MAX_PAGES)
                .toList()
            val output = JSONObject().apply {
                trimmed.reversed().forEach { (pageKey, _, page) -> put(pageKey, page) }
            }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PAGES, output.toString())
                .apply()
        }
    }

    private fun readPages(context: Context): JSONObject {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PAGES, "")
            .orEmpty()
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
