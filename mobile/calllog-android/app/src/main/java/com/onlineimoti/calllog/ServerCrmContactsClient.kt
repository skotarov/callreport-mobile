package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class ServerCrmUserState(
    val userId: String,
    val displayName: String,
    val crmActive: Boolean?,
    val crmUpdatedAtMs: Long,
    val phase: Int?,
    val phaseUpdatedAtMs: Long,
)

internal data class ServerCrmNote(
    val id: String,
    val authorId: String,
    val authorName: String,
    val companyId: String,
    val text: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val editable: Boolean,
)

internal data class ServerCrmClient(
    val identity: String,
    val phone: String,
    val normalizedPhone: String,
    val name: String,
    val lastActivityAtMs: Long,
    val isCrm: Boolean?,
    val crmUpdatedAtMs: Long,
    val phase: Int?,
    val phaseUpdatedAtMs: Long,
    val companyIds: Set<String>,
    val userStates: List<ServerCrmUserState>,
    val notes: List<ServerCrmNote>,
    val searchSnippet: String,
) {
    /** Legacy adapter for older call-log based consumers. The Clients screen does not use it. */
    fun toPhoneCallRecord(): PhoneCallRecord = PhoneCallRecord(
        number = phone,
        name = name,
        direction = "",
        startedAt = lastActivityAtMs,
        durationSeconds = 0L,
        searchSnippet = ServerNoteVisuals.prefixed(searchSnippet),
    )
}

internal data class ServerCrmContactsPage(
    val clients: List<ServerCrmClient>,
    val total: Int,
    val limit: Int,
    val offset: Int,
) {
    /** Legacy adapter kept for older call-log based consumers only. */
    val calls: List<PhoneCallRecord> get() = clients.map(ServerCrmClient::toPhoneCallRecord)
}

/** Reads personal and shared-company Clients records available to the signed-in profile. */
internal object ServerCrmContactsClient {
    private const val PATH = "/relationship-manager/contacts_shared_lookup.php"
    const val DEFAULT_PAGE_SIZE = 20

    /** Backward-compatible first-page reader used by older call sites. */
    fun lookup(
        config: AppConfig,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
        context: Context? = null,
    ): List<PhoneCallRecord> = lookupPage(
        config = config,
        filterState = filterState,
        searchQuery = searchQuery,
        limit = DEFAULT_PAGE_SIZE,
        offset = 0,
        context = context,
    ).calls

    fun lookupPage(
        config: AppConfig,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
        context: Context? = null,
    ): ServerCrmContactsPage {
        if (!CallReportRemoteAccess.isReady(config)) throw IllegalStateException("Remote access is not configured")
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        val endpoint = buildEndpoint(
            config.baseUrl,
            PATH,
            ServerCrmContactsQuery.parameters(config, filterState, searchQuery, safeLimit, safeOffset),
        )
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
                if (!json.optBoolean("ok", false)) throw IllegalStateException("Clients lookup failed")
                return parsePage(json, filterState, safeLimit, safeOffset)
            } catch (error: Throwable) {
                ServerConnectionNotifier.notifyFailure(context, config, error)
                throw error
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parsePage(
        json: JSONObject,
        @Suppress("UNUSED_PARAMETER") filterState: HomeCrmFilterState,
        requestedLimit: Int,
        requestedOffset: Int,
    ): ServerCrmContactsPage {
        val contacts = json.optJSONArray("contacts") ?: json.optJSONArray("items") ?: JSONArray()
        val clients = buildList {
            for (index in 0 until contacts.length()) {
                val item = contacts.optJSONObject(index) ?: continue
                parseClient(item)?.let(::add)
            }
        }.distinctBy { it.identity.ifBlank { it.normalizedPhone } }
        val returnedLimit = json.optInt("limit", requestedLimit).takeIf { it > 0 } ?: requestedLimit
        val returnedOffset = json.optInt("offset", requestedOffset).coerceAtLeast(0)
        val hasTotal = json.has("total") && !json.isNull("total")
        val total = if (hasTotal) {
            json.optInt("total", returnedOffset + clients.size).coerceAtLeast(0)
        } else {
            // Legacy deployments did not return total. Keep Next available only when
            // a full page was returned; new deployments remain fully authoritative.
            returnedOffset + clients.size + if (clients.size >= returnedLimit) 1 else 0
        }
        return ServerCrmContactsPage(clients, total, returnedLimit, returnedOffset)
    }

    private fun parseClient(item: JSONObject): ServerCrmClient? {
        val phone = item.optString("phone").trim().ifBlank { item.optString("number").trim() }
        val normalized = PhoneNormalizer.key(phone)
        if (normalized.isBlank()) return null
        val isCrm = item.booleanOrNull("is_crm")
        val phase = item.intOrNull("phase")?.takeIf(::validPhase)
        val rawSnippet = item.optString("search_match_text").trim()
            .ifBlank { item.optString("search_snippet").trim() }
            .ifBlank { item.optString("matched_note").trim() }
            .ifBlank { item.optString("matched_text").trim() }
        val companies = linkedSetOf<String>().apply {
            addStrings(item.optJSONArray("company_ids"))
            item.optString("company_id").trim().takeIf(String::isNotBlank)?.let(::add)
            item.optString("latest_company_id").trim().takeIf(String::isNotBlank)?.let(::add)
        }
        return ServerCrmClient(
            identity = item.optString("client_id").trim()
                .ifBlank { item.optString("contact_id").trim() }
                .ifBlank { item.optString("id").trim() }
                .ifBlank { normalized },
            phone = phone,
            normalizedPhone = normalized,
            name = item.optString("local_contact_name").trim()
                .ifBlank { item.optString("contact_name").trim() }
                .ifBlank { item.optString("name").trim() },
            lastActivityAtMs = item.optLong("last_activity_at_ms", 0L)
                .coerceAtLeast(item.optLong("updated_at_ms", 0L))
                .coerceAtLeast(item.optLong("created_at_ms", 0L)),
            isCrm = isCrm,
            crmUpdatedAtMs = item.optLong("crm_updated_at_ms", item.optLong("updated_at_ms", 0L)).coerceAtLeast(0L),
            phase = phase,
            phaseUpdatedAtMs = item.optLong("phase_updated_at_ms", item.optLong("updated_at_ms", 0L)).coerceAtLeast(0L),
            companyIds = companies,
            userStates = parseUserStates(item.optJSONArray("user_states")),
            notes = parseNotes(item),
            searchSnippet = rawSnippet,
        )
    }

    private fun parseUserStates(array: JSONArray?): List<ServerCrmUserState> = buildList {
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array?.optJSONObject(index) ?: continue
            val userId = item.optString("user_id").trim().ifBlank { item.optString("id").trim() }
            if (userId.isBlank()) continue
            add(
                ServerCrmUserState(
                    userId = userId,
                    displayName = item.optString("display_name").trim().ifBlank { item.optString("name").trim() },
                    crmActive = item.booleanOrNull("is_crm") ?: item.booleanOrNull("crm_active"),
                    crmUpdatedAtMs = item.optLong("crm_updated_at_ms", 0L).coerceAtLeast(0L),
                    phase = item.intOrNull("phase")?.takeIf(::validPhase),
                    phaseUpdatedAtMs = item.optLong("phase_updated_at_ms", 0L).coerceAtLeast(0L),
                )
            )
        }
    }

    private fun parseNotes(item: JSONObject): List<ServerCrmNote> {
        val array = item.optJSONArray("notes") ?: item.optJSONArray("server_notes")
        val parsed = buildList {
            for (index in 0 until (array?.length() ?: 0)) {
                val note = array?.optJSONObject(index) ?: continue
                parseNote(note)?.let(::add)
            }
        }
        if (parsed.isNotEmpty()) return parsed
        val text = item.optString("latest_server_note").trim().ifBlank { item.optString("latest_note").trim() }
        if (text.isBlank()) return emptyList()
        return listOf(
            ServerCrmNote(
                id = item.optString("latest_server_note_id").trim().ifBlank { "legacy:${item.optLong("latest_server_note_created_at_ms", 0L)}" },
                authorId = item.optString("latest_server_note_author_id").trim(),
                authorName = item.optString("latest_server_note_author_name").trim(),
                companyId = item.optString("latest_company_id").trim(),
                text = text,
                createdAtMs = item.optLong("latest_server_note_created_at_ms", 0L).coerceAtLeast(0L),
                updatedAtMs = item.optLong("latest_server_note_updated_at_ms", item.optLong("latest_server_note_created_at_ms", 0L)).coerceAtLeast(0L),
                editable = item.optBoolean("latest_server_note_editable", false),
            )
        )
    }

    private fun parseNote(note: JSONObject): ServerCrmNote? {
        val text = note.optString("text").trim().ifBlank { note.optString("note").trim() }
        val id = note.optString("id").trim().ifBlank { note.optString("note_id").trim() }
        if (text.isBlank() || id.isBlank()) return null
        return ServerCrmNote(
            id = id,
            authorId = note.optString("author_id").trim().ifBlank { note.optString("user_id").trim() },
            authorName = note.optString("author_name").trim().ifBlank { note.optString("user_name").trim() },
            companyId = note.optString("company_id").trim(),
            text = text,
            createdAtMs = note.optLong("created_at_ms", 0L).coerceAtLeast(0L),
            updatedAtMs = note.optLong("updated_at_ms", note.optLong("created_at_ms", 0L)).coerceAtLeast(0L),
            editable = note.optBoolean("editable", false),
        )
    }

    private fun JSONObject.booleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun MutableSet<String>.addStrings(array: JSONArray?) {
        for (index in 0 until (array?.length() ?: 0)) {
            array?.optString(index)?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun validPhase(value: Int): Boolean =
        value in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4
}

/** Builds one canonical Clients request so every active filter scopes the server query itself. */
internal object ServerCrmContactsQuery {
    fun parameters(
        config: AppConfig,
        filterState: HomeCrmFilterState,
        searchQuery: String,
        limit: Int = ServerCrmContactsClient.DEFAULT_PAGE_SIZE,
        offset: Int = 0,
    ): Map<String, String> {
        val query = searchQuery.trim()
        val phases = filterState.phases.filter {
            it in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4
        }.sorted()
        val companyIds = filterState.companyIds.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        return linkedMapOf(
            "access_token" to config.accessToken,
            "limit" to limit.coerceIn(1, 100).toString(),
            "offset" to offset.coerceAtLeast(0).toString(),
        ).apply {
            if (filterState.crmOnly) put("crm_only", "1")
            if (phases.isNotEmpty()) {
                val phase = phases.joinToString(",")
                put("phase", phase)
                put("phases", phase)
            }
            if (companyIds.isNotEmpty()) put("company_id", companyIds.joinToString(","))
            if (query.isNotBlank()) {
                put("q", query)
                put("search", query)
            }
        }
    }
}
