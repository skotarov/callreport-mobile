package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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
                connection.setRequestProperty("Authorization", "Bearer ${config.accessToken}")
                connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
                connection.setRequestProperty("X-Callreport-Token", config.accessToken)
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
                if (status !in 200..299) throw IllegalStateException("HTTP $status")
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "Contacts lookup failed"))
                val contacts = json.optJSONArray("contacts") ?: json.optJSONArray("items")
                return buildList {
                    for (index in 0 until (contacts?.length() ?: 0)) {
                        val item = contacts?.optJSONObject(index) ?: continue
                        val phone = item.optString("phone").trim().ifBlank { item.optString("number").trim() }
                        if (HomeCallPageLoader.noteKey(phone).isBlank()) continue

                        // The current server is authoritative. The local profile cache
                        // is a defensive fallback during a rolling server deployment.
                        if (filterState.crmOnly) {
                            val serverMarked = item.optBoolean("is_crm", false)
                            val cachedMarked = context?.let { CrmContactSyncStore.isEnabled(it, phone) } == true
                            if (!serverMarked && !cachedMarked) continue
                        }

                        // The server is the primary filter. This defensive check keeps the
                        // Clients list correct if an older deployment ignores the phase
                        // parameter. With no selected phase there is no phase filter.
                        if (filterState.hasPhaseFilter && item.has("phase") && !item.isNull("phase")) {
                            val returnedPhase = item.optInt("phase", ContactNegotiationPhaseStore.NONE)
                            if (returnedPhase !in filterState.phases) continue
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
            } catch (error: Throwable) {
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
        val query = searchQuery.trim()
        return linkedMapOf(
            "access_token" to config.accessToken,
            "crm_only" to if (filterState.crmOnly) "1" else "0",
            "limit" to if (query.isBlank()) "200" else "500",
        ).apply {
            if (filterState.hasPhaseFilter) {
                val phase = filterState.phases.sorted().joinToString(",")
                put("phase", phase)
                // Some older server deployments read only the plural alias.
                put("phases", phase)
            }
            if (filterState.hasCompanyFilter) {
                put("company_id", filterState.companyIds.sorted().joinToString(","))
            }
            if (query.isNotBlank()) {
                put("q", query)
                put("search", query)
            }
        }
    }
}
