package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal object CompanyInvitationApi {
    data class CreatedInvitation(
        val code: String,
        val email: String,
        val role: String,
        val expiresAtMs: Long,
    )

    fun create(
        context: Context,
        companyId: String,
        email: String,
        role: String,
    ): Result<CreatedInvitation> = runCatching {
        val config = ConfigStore.load(context)
        require(config.baseUrl.isNotBlank()) { "Първо задай Server URL в Настройки." }
        require(config.accessToken.isNotBlank()) { "Първо влез в профила." }
        require(companyId.isNotBlank()) { "Липсва избрана фирма." }
        val payload = JSONObject()
            .put("action", "create")
            .put("company_id", companyId.trim())
            .put("email", email.trim())
            .put("role", role)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(buildEndpoint(config.baseUrl, config.invitationsPath, emptyMap())).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.accessToken}")
        }
        try {
            connection.outputStream.use { it.write(payload) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
            if (connection.responseCode !in 200..299 || !response.optBoolean("ok", false)) {
                val error = response.optJSONObject("error")
                throw IllegalStateException(error?.optString("message").orEmpty().ifBlank { "Неуспешно създаване на покана." })
            }
            val invitation = response.optJSONObject("invitation")
            val code = response.optString("invite_code").trim()
            require(code.isNotBlank()) { "Сървърът не върна код за поканата." }
            CreatedInvitation(
                code = code,
                email = invitation?.optString("email").orEmpty().trim(),
                role = invitation?.optString("role").orEmpty().trim(),
                expiresAtMs = invitation?.optLong("expires_at_ms", 0L) ?: 0L,
            )
        } finally {
            connection.disconnect()
        }
    }
}
