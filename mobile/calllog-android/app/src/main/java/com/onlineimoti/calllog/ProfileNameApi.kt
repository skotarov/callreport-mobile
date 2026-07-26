package com.onlineimoti.calllog

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Authenticated client for the backward-compatible profile display-name update action. */
internal object ProfileNameApi {
    private const val AUTH_PATH = "/relationship-manager/api/auth.php"

    fun update(context: android.content.Context, displayName: String): Result<CompanyAccountApi.ProfileUser> = runCatching {
        val safeName = displayName.trim()
        require(safeName.isNotBlank()) { "Въведи име." }
        require(safeName.length <= 120) { "Името е прекалено дълго." }
        val config = ConfigStore.load(context.applicationContext)
        require(config.baseUrl.isNotBlank()) { "Първо задай Server URL в Настройки." }
        require(config.accessToken.isNotBlank()) { "Първо влез в профила." }

        val connection = (URL(buildEndpoint(config.baseUrl, AUTH_PATH, emptyMap())).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.accessToken}")
        }
        try {
            val payload = JSONObject()
                .put("action", "update_profile")
                .put("display_name", safeName)
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
            if (connection.responseCode !in 200..299 || !response.optBoolean("ok", false)) {
                val errorObject = response.optJSONObject("error")
                val message = errorObject?.optString("message").orEmpty()
                    .ifBlank { response.optString("error").trim() }
                    .ifBlank { "Името не можа да бъде променено." }
                throw IllegalStateException(message)
            }
            val user = response.optJSONObject("user")
                ?: throw IllegalStateException("Сървърът не върна профилните данни.")
            CompanyAccountApi.ProfileUser(
                name = user.optString("display_name").trim(),
                email = user.optString("email").trim(),
                phone = user.optString("phone").trim(),
                emailVerified = user.optBoolean("email_verified", false),
                phoneVerified = user.optBoolean("phone_verified", false),
            )
        } finally {
            connection.disconnect()
        }
    }
}
