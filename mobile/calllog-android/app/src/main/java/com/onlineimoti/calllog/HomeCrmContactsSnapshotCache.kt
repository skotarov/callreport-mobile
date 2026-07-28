package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Small stale-while-revalidate cache for already paged Clients results.
 * The key is hashed and includes server, token, filters, page and page size, so
 * snapshots cannot leak between profiles or between different filter states.
 */
internal object HomeCrmContactsSnapshotCache {
    private const val PREFS = "relationship_manager_clients_snapshot_v1"
    private const val KEY_INDEX = "snapshot_index"
    private const val KEY_PREFIX = "snapshot:"
    private const val MAX_DISK_SNAPSHOTS = 24
    private const val MAX_MEMORY_SNAPSHOTS = 8
    private val lock = Any()
    private val memory = object : LinkedHashMap<String, HomeRenderData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HomeRenderData>?): Boolean =
            size > MAX_MEMORY_SNAPSHOTS
    }

    fun read(
        context: Context,
        config: AppConfig,
        filterState: HomeCrmFilterState,
        pageIndex: Int,
        pageSize: Int,
    ): HomeRenderData? = synchronized(lock) {
        val key = requestKey(config, filterState, pageIndex, pageSize)
        memory[key]?.let { return@synchronized it }
        val prefs = preferences(context)
        val raw = prefs.getString(KEY_PREFIX + key, "").orEmpty()
        if (raw.isBlank()) return@synchronized null
        val decoded = HomeCrmContactsSnapshotCodec.decode(raw)
        if (decoded == null) {
            prefs.edit().remove(KEY_PREFIX + key).apply()
            return@synchronized null
        }
        memory[key] = decoded
        decoded
    }

    fun write(
        context: Context,
        config: AppConfig,
        filterState: HomeCrmFilterState,
        pageIndex: Int,
        pageSize: Int,
        data: HomeRenderData,
    ) {
        val encoded = runCatching { HomeCrmContactsSnapshotCodec.encode(data) }.getOrNull() ?: return
        synchronized(lock) {
            val key = requestKey(config, filterState, pageIndex, pageSize)
            val prefs = preferences(context)
            val index = readIndex(prefs.getString(KEY_INDEX, "").orEmpty())
            index.put(key, System.currentTimeMillis())
            val retained = index.keys().asSequence()
                .map { indexedKey -> indexedKey to index.optLong(indexedKey, 0L) }
                .sortedByDescending { it.second }
                .take(MAX_DISK_SNAPSHOTS)
                .toList()
            val retainedKeys = retained.mapTo(linkedSetOf()) { it.first }
            val trimmedIndex = JSONObject().apply {
                retained.reversed().forEach { (indexedKey, savedAt) -> put(indexedKey, savedAt) }
            }
            val allKeys = index.keys().asSequence().toList()
            val editor = prefs.edit()
                .putString(KEY_PREFIX + key, encoded)
                .putString(KEY_INDEX, trimmedIndex.toString())
            allKeys.filter { it !in retainedKeys }.forEach { removedKey ->
                editor.remove(KEY_PREFIX + removedKey)
                memory.remove(removedKey)
            }
            editor.apply()
            memory[key] = data
        }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readIndex(raw: String): JSONObject {
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun requestKey(
        config: AppConfig,
        filterState: HomeCrmFilterState,
        pageIndex: Int,
        pageSize: Int,
    ): String {
        val descriptor = buildString {
            append(config.baseUrl.trim().trimEnd('/'))
            append('\n')
            append(config.accessToken)
            append("\ncrm=")
            append(filterState.crmOnly)
            append("\nphases=")
            append(filterState.phases.sorted().joinToString(","))
            append("\ncompanies=")
            append(filterState.companyIds.sorted().joinToString(","))
            append("\npage=")
            append(pageIndex.coerceAtLeast(0))
            append("\nsize=")
            append(pageSize.coerceAtLeast(1))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(descriptor.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
