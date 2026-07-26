package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class CallReportCompanyMainNote(
    val companyId: String,
    val companyName: String,
    val note: String,
    val updatedAtMs: Long,
    val confirmedByServer: Boolean,
    val pending: Boolean,
    val clientEventId: String = "",
    val authorBrokerId: String = "",
    val authorBrokerName: String = "",
    val editable: Boolean = true,
    val multiAuthor: Boolean = false,
    val placeholder: Boolean = false,
)

internal object CallReportCompanyGeneralNotesClient {
    private const val PATH = "/relationship-manager/history_lookup.php"

    fun fetch(context: android.content.Context, config: AppConfig, phone: String): List<CallReportCompanyMainNote> {
        if (!CallReportRemoteAccess.isReady(config) || phone.isBlank()) return emptyList()
        val connection = URL(buildEndpoint(config.baseUrl, PATH, linkedMapOf("phone" to phone, "limit" to "200")))
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val response = JSONObject(body)
            if (!response.optBoolean("ok", false)) throw IllegalStateException(response.optString("error", "History lookup failed"))
            return CallReportCompanyGeneralNotesParser.parse(context, phone, response)
        } finally {
            connection.disconnect()
        }
    }
}
