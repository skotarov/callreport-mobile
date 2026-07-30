package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class CallReportTopicCompany(
    val id: String,
    val name: String,
    val role: String = "member",
    val canManageUsers: Boolean = false,
    val eik: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
) {
    val canDelete: Boolean get() = role == "owner"
}

/** Loads only the real firms where the current user is an active member. */
internal object CallReportTopicCompaniesClient {
    private const val PATH = "/relationship-manager/company_destinations.php"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    fun fetch(config: AppConfig): List<CallReportTopicCompany> {
        if (!CallReportRemoteAccess.isReady(config)) return emptyList()

        val connection = URL(config.baseUrl.trim().trimEnd('/') + PATH).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.accessToken}")
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val response = runCatching { JSONObject(body) }.getOrNull()
            if (code !in 200..299 || response?.optBoolean("ok", false) != true) {
                val message = response?.optJSONObject("error")?.optString("message")
                    ?: response?.optString("error")
                throw IOException(message.orEmpty().ifBlank { "Company destinations request was rejected." })
            }

            val companies = response.optJSONArray("companies")
            return buildList {
                for (index in 0 until (companies?.length() ?: 0)) {
                    val item = companies?.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim().ifBlank { id }
                    val role = when (item.optString("role", "member").trim().lowercase()) {
                        "owner" -> "owner"
                        "admin" -> "admin"
                        else -> "member"
                    }
                    val canManageUsers = item.optBoolean("can_manage_users", role == "owner" || role == "admin")
                    if (id.isNotBlank()) {
                        add(
                            CallReportTopicCompany(
                                id = id,
                                name = name,
                                role = role,
                                canManageUsers = canManageUsers,
                                eik = item.optString("eik").trim(),
                                createdAtMs = item.optLong("created_at_ms", 0L),
                                updatedAtMs = item.optLong("updated_at_ms", 0L),
                            ),
                        )
                    }
                }
            }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        } finally {
            connection.disconnect()
        }
    }
}