package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable last-known yellow company notes and server markers for the Home timeline. */
internal object HomeCompanyScopeSnapshotCache {
    private const val PREFS = "relationship_manager_home_company_scope_snapshot_v1"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val MAX_PHONE_ENTRIES = 1_000

    fun read(context: Context): HomeCompanyScopeSnapshot {
        val config = ConfigStore.load(context.applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) return HomeCompanyScopeSnapshot()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, "")
            .orEmpty()
        if (raw.isBlank()) return HomeCompanyScopeSnapshot()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optString("account") != accountKey(config)) return@runCatching HomeCompanyScopeSnapshot()
            val labels = linkedMapOf<String, List<HomeCompanyScopeLabel>>()
            val labelsJson = root.optJSONObject("labels")
            labelsJson?.keys()?.forEach { phoneKey ->
                val items = labelsJson.optJSONArray(phoneKey) ?: return@forEach
                val values = buildList {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val companyId = item.optString("company_id").trim()
                        if (companyId.isBlank()) continue
                        add(
                            HomeCompanyScopeLabel(
                                companyId = companyId,
                                companyName = item.optString("company_name").trim().ifBlank { companyId },
                                hasGeneralNote = item.optBoolean("has_general_note"),
                                phase = item.optInt("phase"),
                                generalNote = item.optString("general_note").trim(),
                            ),
                        )
                    }
                }
                if (values.isNotEmpty()) labels[phoneKey] = values
            }
            val serverKeys = linkedSetOf<String>()
            val serverJson = root.optJSONArray("server_backed")
            if (serverJson != null) {
                for (index in 0 until serverJson.length()) {
                    serverJson.optString(index).trim().takeIf { it.isNotBlank() }?.let(serverKeys::add)
                }
            }
            HomeCompanyScopeSnapshot(labels, serverKeys)
        }.getOrDefault(HomeCompanyScopeSnapshot())
    }

    /** Replaces the requested phones while retaining cached data for other Home pages. */
    fun mergeAndStore(
        context: Context,
        requestedPhones: List<String>,
        fresh: HomeCompanyScopeSnapshot,
    ): HomeCompanyScopeSnapshot {
        val current = read(context)
        val requestedKeys = requestedPhones
            .mapTo(linkedSetOf(), HomeCallPageLoader::noteKey)
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        val labels = current.labelsByPhoneKey.toMutableMap().apply {
            requestedKeys.forEach(::remove)
            fresh.labelsByPhoneKey.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotEmpty()) put(key, value)
            }
        }
        val serverKeys = current.serverBackedPhoneKeys.toMutableSet().apply {
            removeAll(requestedKeys)
            addAll(fresh.serverBackedPhoneKeys.filter { it.isNotBlank() })
        }
        val limitedKeys = linkedSetOf<String>().apply {
            labels.keys.toList().takeLast(MAX_PHONE_ENTRIES).forEach(::add)
            serverKeys.toList().takeLast(MAX_PHONE_ENTRIES).forEach(::add)
        }
        val merged = HomeCompanyScopeSnapshot(
            labelsByPhoneKey = labels.filterKeys { it in limitedKeys },
            serverBackedPhoneKeys = serverKeys.filterTo(linkedSetOf()) { it in limitedKeys },
        )
        write(context, merged)
        return merged
    }

    private fun write(context: Context, snapshot: HomeCompanyScopeSnapshot) {
        val config = ConfigStore.load(context.applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) return
        val root = JSONObject().apply {
            put("account", accountKey(config))
            put("saved_at_ms", System.currentTimeMillis())
            put("labels", JSONObject().apply {
                snapshot.labelsByPhoneKey.forEach { (phoneKey, labels) ->
                    put(phoneKey, JSONArray().apply {
                        labels.forEach { label ->
                            put(JSONObject().apply {
                                put("company_id", label.companyId)
                                put("company_name", label.companyName)
                                put("has_general_note", label.hasGeneralNote)
                                put("phase", label.phase)
                                put("general_note", label.generalNote)
                            })
                        }
                    })
                }
            })
            put("server_backed", JSONArray().apply {
                snapshot.serverBackedPhoneKeys.forEach(::put)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, root.toString())
            .apply()
    }

    private fun accountKey(config: AppConfig): String =
        "${config.baseUrl.trim()}#${config.accessToken.trim()}".hashCode().toString()
}