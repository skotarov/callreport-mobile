package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Owner-only company management actions. */
internal object CompanyManagementApi {
    private const val PATH = "/relationship-manager/company_profile.php"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    fun delete(config: AppConfig, companyId: String) {
        val response = request(
            config,
            JSONObject()
                .put("action", "delete")
                .put("company_id", companyId.trim()),
        )
        if (!response.optBoolean("deleted", false)) {
            throw IOException("Server did not confirm the company deletion.")
        }
    }

    private fun request(config: AppConfig, payload: JSONObject): JSONObject {
        if (!CallReportRemoteAccess.isReady(config)) throw IOException("Cloud profile is not connected.")
        val connection = URL(config.baseUrl.trim().trimEnd('/') + PATH).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.accessToken}")
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val response = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            if (code !in 200..299 || !response.optBoolean("ok", false)) {
                val message = response.optJSONObject("error")?.optString("message")
                    ?: response.optString("error")
                throw IOException(message.orEmpty().ifBlank { "Company management request was rejected." })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}