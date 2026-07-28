package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** HTTP client for the company-scoped current negotiation phase. */
internal object ContactNegotiationPhaseRemoteClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    fun fetch(config: AppConfig, phone: String): ContactNegotiationPhaseState {
        val endpoint = buildEndpoint(config.baseUrl, config.contactPhasePath, linkedMapOf("phone" to phone))
        return request(config, endpoint, method = "GET", payload = null)
    }

    fun update(config: AppConfig, phone: String, state: ContactNegotiationPhaseState): ContactNegotiationPhaseState {
        val endpoint = config.baseUrl.trim().trimEnd('/') + config.contactPhasePath
        val payload = JSONObject().apply {
            put("phone", phone)
            put("phase", state.phase)
            put("updated_at_ms", state.updatedAtMs)
        }
        return request(config, endpoint, method = "POST", payload = payload)
    }

    private fun request(
        config: AppConfig,
        endpoint: String,
        method: String,
        payload: JSONObject?,
    ): ContactNegotiationPhaseState {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { output ->
                    output.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input -> BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (status !in 200..299 || json?.optBoolean("ok", false) != true) {
                val error = json?.optString("error").orEmpty().trim()
                throw IOException(if (error.isNotBlank()) "$error (HTTP $status)" else "Contact phase request failed ($status).")
            }
            return parse(json)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(response: JSONObject): ContactNegotiationPhaseState {
        val phase = response.optJSONObject("contact_phase")
            ?: throw IOException("Missing contact phase in server response.")
        return ContactNegotiationPhaseState(
            phase = phase.optInt("phase", ContactNegotiationPhaseStore.NONE),
            updatedAtMs = phase.optLong("updated_at_ms", 0L).coerceAtLeast(0L),
        )
    }
}
