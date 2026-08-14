package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable authorization for sharing one exact phone call because the user
 * explicitly attached a company-scoped note to that call.
 *
 * This never enables future communication for the phone. The marker is removed
 * only after the corresponding phone event is confirmed by the server.
 */
internal object CompanySharedCallStore {
    private const val PREFS = "company_shared_calls"
    private const val KEY_ROWS = "rows_v1"
    private const val MAX_AGE_MS = 90L * 24L * 60L * 60L * 1000L
    private val lock = Any()

    fun mark(context: Context, phone: String, direction: String, occurredAtMs: Long) {
        val key = PhoneNormalizer.key(phone)
        if (key.isBlank() || occurredAtMs <= 0L) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val rows = readLocked(context.applicationContext)
                .filter { now - it.markedAtMs <= MAX_AGE_MS }
                .filterNot { it.matches(key, direction, occurredAtMs) }
                .toMutableList()
            rows += Row(key, direction.trim(), occurredAtMs, now)
            writeLocked(context.applicationContext, rows)
        }
    }

    fun isMarked(context: Context, phone: String, direction: String, occurredAtMs: Long): Boolean {
        val key = PhoneNormalizer.key(phone)
        if (key.isBlank() || occurredAtMs <= 0L) return false
        return synchronized(lock) {
            val now = System.currentTimeMillis()
            val all = readLocked(context.applicationContext)
            val fresh = all.filter { now - it.markedAtMs <= MAX_AGE_MS }
            if (fresh.size != all.size) writeLocked(context.applicationContext, fresh)
            fresh.any { it.matches(key, direction, occurredAtMs) }
        }
    }

    fun clear(context: Context, phone: String, direction: String, occurredAtMs: Long) {
        val key = PhoneNormalizer.key(phone)
        if (key.isBlank() || occurredAtMs <= 0L) return
        synchronized(lock) {
            writeLocked(
                context.applicationContext,
                readLocked(context.applicationContext).filterNot {
                    it.matches(key, direction, occurredAtMs)
                },
            )
        }
    }

    private fun readLocked(context: Context): List<Row> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROWS, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val phoneKey = item.optString("phone_key").trim()
                val occurredAtMs = item.optLong("occurred_at_ms", 0L)
                if (phoneKey.isBlank() || occurredAtMs <= 0L) continue
                add(
                    Row(
                        phoneKey = phoneKey,
                        direction = item.optString("direction").trim(),
                        occurredAtMs = occurredAtMs,
                        markedAtMs = item.optLong("marked_at_ms", 0L)
                            .takeIf { it > 0L } ?: System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun writeLocked(context: Context, rows: List<Row>) {
        val payload = JSONArray().apply {
            rows.forEach { row ->
                put(JSONObject().apply {
                    put("phone_key", row.phoneKey)
                    put("direction", row.direction)
                    put("occurred_at_ms", row.occurredAtMs)
                    put("marked_at_ms", row.markedAtMs)
                })
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROWS, payload.toString()).commit()
    }

    private data class Row(
        val phoneKey: String,
        val direction: String,
        val occurredAtMs: Long,
        val markedAtMs: Long,
    ) {
        fun matches(otherPhoneKey: String, otherDirection: String, otherOccurredAtMs: Long): Boolean =
            phoneKey == otherPhoneKey &&
                occurredAtMs == otherOccurredAtMs &&
                (direction.isBlank() || otherDirection.isBlank() || direction == otherDirection)
    }
}
