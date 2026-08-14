package com.onlineimoti.calllog

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal class CallReportSyncException(
    message: String,
    val retryable: Boolean,
) : IOException(message)

/** HTTP client for the standalone Relationship Manager sync endpoint. */
internal object CallReportSyncClient {
    private const val SYNC_PATH = "/relationship-manager/api/sync_edit.php"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** Returns only client event IDs explicitly confirmed in the server response. */
    fun sync(config: AppConfig, events: List<CallReportSyncEvent>): Set<String> {
        if (events.isEmpty()) return emptySet()
        require(events.size <= 50) { "A sync request may contain up to 50 events." }

        val payload = JSONObject().apply {
            put("schema_version", 1)
            put("events", JSONArray().apply {
                events.forEach { event -> put(event.toJson()) }
            })
        }
        val endpoint = config.baseUrl.trim().trimEnd('/') + SYNC_PATH
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            // Older deployed builds also accept this header; both carry the same token.
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input -> BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText() }.orEmpty()
            val response = runCatching { JSONObject(body) }.getOrNull()
            if (responseCode !in 200..299 || response?.optBoolean("ok", false) != true) {
                val retryable = responseCode == 408 || responseCode == 429 || responseCode >= 500 || responseCode == 0
                val serverError = response?.optString("error").orEmpty().trim()
                val message = if (serverError.isNotBlank()) {
                    "$serverError (HTTP $responseCode)"
                } else {
                    "Sync request was rejected ($responseCode)."
                }
                throw CallReportSyncException(message, retryable)
            }
            return response.optJSONArray("results")
                ?.let { results ->
                    buildSet {
                        for (index in 0 until results.length()) {
                            results.optJSONObject(index)
                                ?.optString("client_event_id")
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                }
                .orEmpty()
        } catch (error: CallReportSyncException) {
            throw error
        } catch (error: IOException) {
            throw CallReportSyncException("Network error while syncing communication events.", retryable = true)
        } finally {
            connection.disconnect()
        }
    }

    private fun CallReportSyncEvent.toJson(): JSONObject = JSONObject().apply {
        put("client_event_id", clientEventId)
        put("communication_type", communicationType)
        put("direction", direction)
        put("status", status)
        put("phone", phone)
        put("contact_name", contactName)
        put("occurred_at_ms", occurredAtMs)
        put("duration_seconds", durationSeconds)
        if (companyId.isNotBlank()) put("company_id", companyId)
        note?.let { value -> put("note", value) }
        if (updatedAtMs > 0L) put("updated_at_ms", updatedAtMs)
        if (editExistingNote) {
            put("edit_existing_note", true)
            if (note.isNullOrBlank()) put("deleted", true)
        }
        put("source", JSONObject().apply {
            put("device_id", deviceId)
            put("provider_row_id", providerRowId)
            put("app_version", appVersion)
        })
    }
}
