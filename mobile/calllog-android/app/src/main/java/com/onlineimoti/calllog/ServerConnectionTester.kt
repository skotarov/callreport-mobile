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

    fun test(config: AppConfig): Result {
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        val token = config.accessToken.trim()
        require(baseUrl.isNotBlank()) { "Липсва Server URL." }
        require(
            baseUrl.startsWith("https://", ignoreCase = true) ||
                (BuildConfig.DEBUG && baseUrl.startsWith("http://", ignoreCase = true)),
        ) { "Server URL трябва да започва с https://." }
        require(token.isNotBlank()) { "Липсва access token." }
        val endpoint = buildEndpoint(baseUrl, config.authPath, emptyMap())
        val payload = JSONObject().put("action", "me").toString().toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-Relationship-Manager-Token", token)
            setRequestProperty("X-Callreport-Token", token)
        }
        try {
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty().trim()
            if (code !in 200..299) {
                return Result(
                    ok = false,
                    title = "Връзката стигна до сървъра, но token-ът не беше приет.",
                    detail = httpErrorText(code, body),
                    endpoint = endpoint,
                    httpCode = code,
                )
            }
            if (body.isBlank()) {
                return Result(
                    ok = false,
                    title = "Сървърът отговори, но отговорът е празен.",
                    detail = "Провери auth path: ${config.authPath}",
                    endpoint = endpoint,
                    httpCode = code,
                )
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return Result(
                    ok = false,
                    title = "Сървърът отговори, но не върна JSON.",
                    detail = "Base URL или auth path вероятно сочат към HTML страница, а не към API.",
                    endpoint = endpoint,
                    httpCode = code,
                )
            if (!json.optBoolean("ok", false)) {
                return Result(
                    ok = false,
                    title = "Връзката работи, но token-ът не е валиден.",
                    detail = errorMessage(json).ifBlank { "Провери access token и auth path." },
                    endpoint = endpoint,
                    httpCode = code,
                )
            }
            val user = json.optJSONObject("user")
            val profileName = user?.optString("display_name").orEmpty().trim()
            val profileEmail = user?.optString("email").orEmpty().trim()
            if (user == null) {
                return Result(
                    ok = false,
                    title = "Token-ът е приет, но сървърът не върна профил.",
                    detail = "Провери отговора на ${config.authPath} за action=me.",
                    endpoint = endpoint,
                    httpCode = code,
                )
            }
            return Result(
                ok = true,
                title = "Връзката със сървъра е активна.",
                detail = buildString {
                    append("HTTP ").append(code)
                    if (profileName.isNotBlank()) append("\nПрофил: ").append(profileName)
                    if (profileEmail.isNotBlank()) append("\nИмейл: ").append(profileEmail)
                    append("\nauth path: ").append(config.authPath)
                },
                endpoint = endpoint,
                httpCode = code,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun httpErrorText(code: Int, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val message = json?.let(::errorMessage).orEmpty().ifBlank { body.take(240) }
        return buildString {
            append("HTTP $code")
            if (message.isNotBlank()) append("\n").append(message)
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
