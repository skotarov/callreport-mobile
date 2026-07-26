package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Mobile client for profile authentication and separate company creation. */
internal object CompanyAccountApi {
    private const val AUTH_PATH = "/relationship-manager/api/auth.php"

    data class Session(
        val accessToken: String,
        val userName: String,
        val organizationName: String,
        val organizationId: String,
    )

    fun registerProfile(
        context: Context,
        email: String,
        password: String,
        displayName: String,
    ): Result<Session> = post(
        context,
        JSONObject()
            .put("action", "register_profile")
            .put("email", email.trim())
            .put("password", password)
            .put("display_name", displayName.trim())
            .put("device_name", android.os.Build.MODEL.take(120)),
    )

    fun createCompany(
        context: Context,
        organizationName: String,
        organizationEik: String,
        activationToken: String,
    ): Result<Session> = post(
        context,
        JSONObject()
            .put("action", "create_company")
            .put("organization_name", organizationName.trim())
            .put("organization_eik", organizationEik.trim())
            .put("activation_token", activationToken.trim())
            .put("device_name", android.os.Build.MODEL.take(120)),
        authenticated = true,
    )

    /** Backward-compatible combined registration for older callers. */
    fun register(
        context: Context,
        email: String,
        password: String,
        displayName: String,
        organizationName: String,
        organizationEik: String,
        activationToken: String,
    ): Result<Session> = post(
        context,
        JSONObject()
            .put("action", "register")
            .put("email", email.trim())
            .put("password", password)
            .put("display_name", displayName.trim())
            .put("organization_name", organizationName.trim())
            .put("organization_eik", organizationEik.trim())
            .put("activation_token", activationToken.trim())
            .put("device_name", android.os.Build.MODEL.take(120)),
    )

    fun login(context: Context, email: String, password: String): Result<Session> = post(
        context,
        JSONObject()
            .put("action", "login_profile")
            .put("email", email.trim())
            .put("password", password)
            .put("device_name", android.os.Build.MODEL.take(120)),
    )

    fun logout(context: Context): Result<Unit> = runCatching {
        val config = ConfigStore.load(context)
        if (config.baseUrl.isBlank() || config.accessToken.isBlank()) return@runCatching
        val payload = JSONObject()
            .put("action", "logout")
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(buildEndpoint(config.baseUrl, AUTH_PATH, emptyMap())).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
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
                throw IllegalStateException(error?.optString("message").orEmpty().ifBlank { "Сървърната сесия не можа да бъде прекратена." })
            }
        } finally {
            connection.disconnect()
        }
    }

    fun applySession(context: Context, session: Session) {
        val current = ConfigStore.load(context)
        ConfigStore.save(
            context,
            current.copy(
                remoteEnabled = true,
                accessToken = session.accessToken,
            ),
        )
        CompanySessionStore.save(context, session)
    }

    fun clearSession(context: Context) {
        val current = ConfigStore.load(context)
        ConfigStore.save(
            context,
            current.copy(
                remoteEnabled = false,
                accessToken = "",
            ),
        )
        CompanySessionStore.clear(context)
    }

    private fun post(
        context: Context,
        payload: JSONObject,
        authenticated: Boolean = false,
    ): Result<Session> = runCatching {
        val config = ConfigStore.load(context)
        require(config.baseUrl.isNotBlank()) { "Първо задай Server URL в Настройки." }
        if (authenticated) require(config.accessToken.isNotBlank()) { "Първо влез в профила." }
        val connection = (URL(buildEndpoint(config.baseUrl, AUTH_PATH, emptyMap())).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (authenticated) setRequestProperty("Authorization", "Bearer ${config.accessToken}")
        }
        try {
            val body = payload.toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
            if (connection.responseCode !in 200..299 || !response.optBoolean("ok", false)) {
                val error = response.optJSONObject("error")
                throw IllegalStateException(error?.optString("message").orEmpty().ifBlank { "Неуспешна заявка към сървъра." })
            }
            if (response.optBoolean("selection_required", false)) {
                val organizations = response.optJSONArray("organizations")
                val selectedId = organizations?.optJSONObject(0)?.optString("id").orEmpty().trim()
                require(selectedId.isNotBlank()) { "Няма достъпна фирма за този профил." }
                val retry = JSONObject(payload.toString()).put("organization_id", selectedId)
                return@runCatching post(context, retry, authenticated).getOrThrow()
            }
            val accessToken = response.optString("access_token").trim()
            require(accessToken.isNotBlank()) { "Сървърът не върна валиден access token." }
            val user = response.optJSONObject("user")
            val organization = response.optJSONObject("organization")
            Session(
                accessToken = accessToken,
                userName = user?.optString("display_name").orEmpty().trim(),
                organizationName = organization?.optString("name").orEmpty().trim(),
                organizationId = organization?.optString("id").orEmpty().trim(),
            )
        } finally {
            connection.disconnect()
        }
    }
}
