package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Authenticated client for updating the profile display name. */
internal object ProfileNameApi {
    private const val AUTH_PATH = "/relationship-manager/api/auth.php"

    /**
     * Keeps the existing UI contract while making the save local-first. The durable
     * outbox performs the actual HTTP request when a network is available.
     */
    fun update(context: Context, displayName: String): Result<CompanyAccountApi.ProfileUser> = runCatching {
        val appContext = context.applicationContext
        AccountMutationOutbox.enqueueProfileName(appContext, displayName).getOrThrow()
        val profile = CompanySessionStore.load(appContext)
            ?: throw IllegalStateException("Профилът не можа да бъде записан локално.")
        CompanyAccountApi.ProfileUser(
            name = profile.userName,
            email = profile.userEmail,
            phone = profile.userPhone,
            emailVerified = profile.emailVerified,
            phoneVerified = profile.phoneVerified,
        )
    }

    /** Called only by [AccountMutationWorker] after its network constraint is met. */
    internal fun updateRemote(
        context: Context,
        displayName: String,
    ): Result<CompanyAccountApi.ProfileUser> = runCatching {
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
            setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            setRequestProperty("X-Callreport-Token", config.accessToken)
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
