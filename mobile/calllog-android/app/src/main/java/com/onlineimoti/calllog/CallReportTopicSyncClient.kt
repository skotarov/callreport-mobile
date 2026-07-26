package com.onlineimoti.calllog

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal object CallReportTopicSyncClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    fun sync(config: AppConfig, events: List<CallReportTopicSyncEvent>): Set<String> {
        if (!CallReportRemoteAccess.isReady(config) || events.isEmpty()) return emptySet()
        val payload = JSONObject().apply {
            put("schema_version", 1)
            put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
        }
        val connection = URL(config.baseUrl.trim().trimEnd('/') + config.syncPath).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val response = runCatching { JSONObject(body) }.getOrNull()
            if (code !in 200..299 || response?.optBoolean("ok", false) != true) {
                throw IOException(response?.optString("error").orEmpty().ifBlank { "Topic sync failed." })
            }
            return response.optJSONArray("results")?.let { results ->
                buildSet {
                    for (index in 0 until results.length()) {
                        val item = results.optJSONObject(index) ?: continue
                        val confirmed = item.optBoolean("stored", false) ||
                            item.optBoolean("deleted", false) ||
                            item.optBoolean("removed", false)
                        if (!confirmed) continue
                        item.optString("client_event_id").trim()
                            .takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private fun CallReportTopicSyncEvent.toJson(): JSONObject = JSONObject().apply {
        put("client_event_id", clientEventId)
        if (companyId.isNotBlank()) put("company_id", companyId)
        put("communication_type", communicationType)
        put("direction", direction)
        put("status", "")
        put("phone", phone)
        put("contact_name", contactName)
        put("occurred_at_ms", occurredAtMs)
        put("duration_seconds", durationSeconds)
        put("note", note)
        val isCompanyNote = companyId.isNotBlank() &&
            communicationType.equals("note", ignoreCase = true)
        val isCompanyMainNote = isCompanyNote &&
            direction.isBlank() &&
            durationSeconds <= 0L &&
            clientEventId.contains(":topic:general:")
        if (isCompanyMainNote) put("note_scope", "company_main")
        if (isCompanyNote && note.isBlank()) put("deleted", true)
        if (clearCompanyAssignment) put("clear_company_assignment", true)
        put("source", JSONObject().apply {
            put("channel", "android")
            put("device_id", deviceId)
            put("provider_row_id", clientEventId)
            put("app_version", appVersion)
        })
    }
}

internal data class CallReportTopicSyncEvent(
    val clientEventId: String,
    val companyId: String,
    val phone: String,
    val direction: String,
    val occurredAtMs: Long,
    val durationSeconds: Long,
    val note: String,
    val contactName: String,
    val deviceId: String,
    val appVersion: String,
    val communicationType: String = "note",
    val clearCompanyAssignment: Boolean = false,
)
