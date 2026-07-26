package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Reads personal and shared-company CRM contacts available to the signed-in profile. */
internal object ServerCrmContactsClient {
    private const val PATH = "/relationship-manager/contacts_shared_lookup.php"

    fun lookup(
        config: AppConfig,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
        context: Context? = null,
    ): List<PhoneCallRecord> {
        if (!CallReportRemoteAccess.isReady(config)) return emptyList()
        ServerCrmContactsPhaseHints.clear()
        val endpoint = buildEndpoint(config.baseUrl, PATH, queryParameters(config, filterState, searchQuery))
        val connection = runCatching { URL(endpoint).openConnection() as HttpURLConnection }.getOrElse { error ->
            ServerConnectionNotifier.notifyFailure(context, config, error)
            throw error
        }
        try {
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
                connection.setRequestProperty("X-Callreport-Token", config.accessToken)
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
                if (status !in 200..299) throw IllegalStateException("HTTP $status")
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "Contacts lookup failed"))
                val contacts = json.optJSONArray("contacts") ?: json.optJSONArray("items")
                val phaseHints = linkedMapOf<String, ServerCrmContactPhaseHint>()
                val records = buildList {
                    for (index in 0 until (contacts?.length() ?: 0)) {
                        val item = contacts?.optJSONObject(index) ?: continue
                        val phone = item.optString("phone").trim().ifBlank { item.optString("number").trim() }
                        val phoneKey = HomeCallPageLoader.noteKey(phone)
                        if (phoneKey.isBlank()) continue
                        if (item.has("phase") && !item.isNull("phase")) {
                            phaseHints[phoneKey] = ServerCrmContactPhaseHint(
                                companyId = item.optString("company_id").trim(),
                                phase = item.optInt("phase", ContactNegotiationPhaseStore.NONE),
                            )
                        }
                        val rawSnippet = item.optString("search_match_text").trim()
                            .ifBlank { item.optString("search_snippet").trim() }
                            .ifBlank { item.optString("matched_note").trim() }
                            .ifBlank { item.optString("matched_text").trim() }
                        add(
                            PhoneCallRecord(
                                number = phone,
                                name = item.optString("local_contact_name").trim()
                                    .ifBlank { item.optString("contact_name").trim() }
                                    .ifBlank { item.optString("name").trim() },
                                direction = "",
                                startedAt = item.optLong("last_activity_at_ms", 0L)
                                    .coerceAtLeast(item.optLong("updated_at_ms", 0L))
                                    .coerceAtLeast(item.optLong("created_at_ms", 0L)),
                                durationSeconds = 0L,
                                searchSnippet = ServerNoteVisuals.prefixed(rawSnippet),
                            ),
                        )
                    }
                }.distinctBy { HomeCallPageLoader.noteKey(it.number) }
                ServerCrmContactsPhaseHints.replace(phaseHints)
                return records
            } catch (error: Throwable) {
                ServerCrmContactsPhaseHints.clear()
                ServerConnectionNotifier.notifyFailure(context, config, error)
                throw error
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun queryParameters(
        config: AppConfig,
        filterState: HomeCrmFilterState,
        searchQuery: String,
    ): Map<String, String> {
        val companyId = filterState.companyIds
            .takeIf { it.isNotEmpty() }
            ?.sorted()
            ?.joinToString(",")
            ?: "all"
        val query = searchQuery.trim()
        return linkedMapOf(
            "access_token" to config.accessToken,
            // The Clients page needs the complete company-scoped candidate set.
            // Android then combines confirmed server phases with just-edited local
            // phase state before filtering, so a pending sync cannot produce an
            // incorrectly empty list.
            "phase" to "all",
            "phases" to "all",
            "company_id" to companyId,
            "limit" to if (query.isBlank()) "200" else "500",
        ).apply {
            if (query.isNotBlank()) {
                put("q", query)
                put("search", query)
            }
        }
    }
}

internal data class ServerCrmContactPhaseHint(
    val companyId: String,
    val phase: Int,
)

/** Phase metadata returned together with the current Clients candidate list. */
internal object ServerCrmContactsPhaseHints {
    private val values = ConcurrentHashMap<String, ServerCrmContactPhaseHint>()

    fun replace(next: Map<String, ServerCrmContactPhaseHint>) {
        values.clear()
        values.putAll(next)
    }

    fun clear() = values.clear()

    fun forPhone(phone: String): ServerCrmContactPhaseHint? =
        values[HomeCallPageLoader.noteKey(phone)]
}
