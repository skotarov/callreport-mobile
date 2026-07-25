package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class CompanyManagedUser(
    val id: String,
    val name: String,
    val email: String,
    val role: String,
    val active: Boolean,
    val isCurrentUser: Boolean,
    val canDeactivate: Boolean,
    val canGenerateKey: Boolean,
)

internal data class CompanyUsersSnapshot(
    val company: CallReportTopicCompany,
    val users: List<CompanyManagedUser>,
)

internal data class GeneratedCompanyAccessKey(
    val user: CompanyManagedUser,
    val key: String,
)

internal object CompanyUsersApi {
    private const val PATH = "/relationship-manager/company_users.php"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    fun list(config: AppConfig, company: CallReportTopicCompany): CompanyUsersSnapshot {
        val response = request(config, JSONObject().put("action", "list").put("company_id", company.id))
        val users = response.optJSONArray("users")
        return CompanyUsersSnapshot(
            company = company,
            users = buildList {
                for (index in 0 until (users?.length() ?: 0)) {
                    val item = users?.optJSONObject(index) ?: continue
                    add(parseUser(item))
                }
            },
        )
    }

    fun deactivate(config: AppConfig, companyId: String, userId: String): CompanyManagedUser {
        val response = request(
            config,
            JSONObject()
                .put("action", "deactivate")
                .put("company_id", companyId)
                .put("user_id", userId),
        )
        return response.optJSONObject("user")?.let(::parseUser)
            ?: throw IOException("Server did not return the updated user.")
    }

    fun generateKey(config: AppConfig, companyId: String, userId: String): GeneratedCompanyAccessKey {
        val response = request(
            config,
            JSONObject()
                .put("action", "generate_key")
                .put("company_id", companyId)
                .put("user_id", userId),
        )
        val key = response.optString("access_key").trim()
        val user = response.optJSONObject("user")?.let(::parseUser)
        if (key.isBlank() || user == null) throw IOException("Server did not return a valid access key.")
        return GeneratedCompanyAccessKey(user, key)
    }

    private fun request(config: AppConfig, payload: JSONObject): JSONObject {
        if (!CallReportRemoteAccess.isReady(config)) throw IOException("Cloud account is not configured.")
        val connection = URL(config.baseUrl.trim().trimEnd('/') + PATH).openConnection() as HttpURLConnection
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
            val response = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            if (code !in 200..299 || !response.optBoolean("ok", false)) {
                val message = response.optJSONObject("error")?.optString("message")
                    ?: response.optString("error")
                throw IOException(message.orEmpty().ifBlank { "Company user request was rejected." })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseUser(item: JSONObject): CompanyManagedUser {
        val id = item.optString("id").trim()
        return CompanyManagedUser(
            id = id,
            name = item.optString("name").trim().ifBlank { id },
            email = item.optString("email").trim(),
            role = item.optString("role", "broker").trim().lowercase().ifBlank { "broker" },
            active = item.optBoolean("active", true),
            isCurrentUser = item.optBoolean("is_current_user", false),
            canDeactivate = item.optBoolean("can_deactivate", false),
            canGenerateKey = item.optBoolean("can_generate_key", false),
        )
    }
}
