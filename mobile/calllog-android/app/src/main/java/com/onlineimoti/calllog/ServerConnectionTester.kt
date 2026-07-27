package com.onlineimoti.calllog

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal object ServerConnectionTester {
    data class Result(
        val ok: Boolean,
        val title: String,
        val detail: String,
        val endpoint: String,
        val httpCode: Int = 0,
    )

    private data class Probe(
        val endpoint: String,
        val httpCode: Int,
        val body: String,
        val json: JSONObject?,
    )

    fun test(config: AppConfig): Result {
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        val token = config.accessToken.trim()
        require(baseUrl.isNotBlank()) { "Липсва Server URL." }
        require(
            baseUrl.startsWith("https://", ignoreCase = true) ||
                (BuildConfig.DEBUG && baseUrl.startsWith("http://", ignoreCase = true)),
        ) { "Server URL трябва да започва с https://." }
        require(token.isNotBlank()) { "Липсва access token." }

        val profileProbe = requestJson(
            endpoint = buildEndpoint(baseUrl, config.authPath, emptyMap()),
            token = token,
            method = "POST",
            payload = JSONObject().put("action", "me"),
        )
        profileSuccess(profileProbe)?.let { return it }

        // Older installations may still use a valid account token that predates
        // profile sessions. auth.php?action=me correctly rejects that token because
        // it has no profile user, while the ordinary RM endpoints still accept it.
        // config.php is read-only and uses the same account-level authentication as
        // lookup/history/sync, so it is the compatibility-safe fallback probe.
        val accountProbe = requestJson(
            endpoint = buildEndpoint(baseUrl, config.configPath, emptyMap()),
            token = token,
            method = "GET",
            payload = null,
        )
        accountSuccess(accountProbe, config)?.let { return it }

        return Result(
            ok = false,
            title = "Връзката стигна до сървъра, но token-ът не беше приет.",
            detail = buildString {
                append("Профилен достъп: ").append(probeFailure(profileProbe, config.authPath))
                append("\nДостъп до акаунта: ").append(probeFailure(accountProbe, config.configPath))
            },
            endpoint = accountProbe.endpoint,
            httpCode = accountProbe.httpCode.takeIf { it > 0 } ?: profileProbe.httpCode,
        )
    }

    private fun profileSuccess(probe: Probe): Result? {
        val json = probe.json ?: return null
        if (probe.httpCode !in 200..299 || !json.optBoolean("ok", false)) return null
        val user = json.optJSONObject("user") ?: return null
        val profileName = user.optString("display_name").trim()
        val profileEmail = user.optString("email").trim()
        return Result(
            ok = true,
            title = "Връзката със сървъра е активна.",
            detail = buildString {
                append("HTTP ").append(probe.httpCode)
                if (profileName.isNotBlank()) append("\nПрофил: ").append(profileName)
                if (profileEmail.isNotBlank()) append("\nИмейл: ").append(profileEmail)
                append("\nПроверка: профилен token")
            },
            endpoint = probe.endpoint,
            httpCode = probe.httpCode,
        )
    }

    private fun accountSuccess(probe: Probe, config: AppConfig): Result? {
        val json = probe.json ?: return null
        if (probe.httpCode !in 200..299 || !json.optBoolean("ok", false)) return null
        val account = json.optJSONObject("account") ?: return null
        val accountName = account.optString("name").trim()
        val accountId = account.optString("id").trim()
        return Result(
            ok = true,
            title = "Връзката със сървъра е активна.",
            detail = buildString {
                append("HTTP ").append(probe.httpCode)
                if (accountName.isNotBlank()) append("\nАкаунт: ").append(accountName)
                if (accountId.isNotBlank()) append("\nID: ").append(accountId)
                append("\nПроверка: съвместим account token")
                append("\nconfig path: ").append(config.configPath)
            },
            endpoint = probe.endpoint,
            httpCode = probe.httpCode,
        )
    }

    private fun requestJson(
        endpoint: String,
        token: String,
        method: String,
        payload: JSONObject?,
    ): Probe {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-Relationship-Manager-Token", token)
            setRequestProperty("X-Callreport-Token", token)
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (payload != null) {
                val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty().trim()
            return Probe(
                endpoint = endpoint,
                httpCode = code,
                body = body,
                json = body.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun probeFailure(probe: Probe, path: String): String {
        if (probe.httpCode <= 0) return "няма HTTP отговор от $path"
        if (probe.body.isBlank()) return "HTTP ${probe.httpCode}, празен отговор от $path"
        val json = probe.json ?: return "HTTP ${probe.httpCode}, отговорът от $path не е JSON"
        val message = errorMessage(json)
        return buildString {
            append("HTTP ").append(probe.httpCode)
            if (message.isNotBlank()) append(" — ").append(message)
            else if (json.optBoolean("ok", false)) append(" — липсват очакваните данни")
        }
    }

    private fun errorMessage(json: JSONObject): String {
        val errorObject = json.optJSONObject("error")
        return errorObject?.optString("message").orEmpty()
            .ifBlank { json.optString("message") }
            .ifBlank { json.optString("error") }
            .trim()
    }
}
