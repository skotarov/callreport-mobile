package com.onlineimoti.calllog

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object ServerConnectionTester {
    data class Result(
        val ok: Boolean,
        val title: String,
        val detail: String,
        val endpoint: String,
        val httpCode: Int = 0,
    )

    fun test(config: AppConfig): Result {
        require(config.baseUrl.isNotBlank()) { "Липсва Server URL." }
        require(config.accessToken.isNotBlank()) { "Липсва access token." }
        val endpoint = buildEndpoint(
            config.baseUrl,
            config.lookupPath,
            mapOf(
                "access_token" to config.accessToken,
                "phone" to "+359000000000",
                "direction" to "test",
                "source" to "android_settings_test",
            ),
        )
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.accessToken}")
            setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            setRequestProperty("X-Callreport-Token", config.accessToken)
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty().trim()
            if (code !in 200..299) {
                return Result(
                    ok = false,
                    title = "Връзката стигна до сървъра, но той върна грешка.",
                    detail = httpErrorText(code, body),
                    endpoint = endpoint,
                    httpCode = code,
                )
            }
            if (body.isBlank()) {
                return Result(
                    ok = false,
                    title = "Сървърът отговори, но отговорът е празен.",
                    detail = "Провери lookup path: ${config.lookupPath}",
                    endpoint = endpoint,
                    httpCode = code,
                )
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return Result(
                    ok = false,
                    title = "Сървърът отговори, но не върна JSON.",
                    detail = "Вероятно Base URL или lookup path сочат към HTML страница, не към API.",
                    endpoint = endpoint,
                    httpCode = code,
                )
            if (json.has("ok") && !json.optBoolean("ok", false)) {
                return Result(
                    ok = false,
                    title = "Връзката работи, но настройките не са приети.",
                    detail = errorMessage(json).ifBlank { "Провери access token и lookup path." },
                    endpoint = endpoint,
                    httpCode = code,
                )
            }
            return Result(
                ok = true,
                title = "Връзката със сървъра е OK.",
                detail = listOf(
                    "HTTP $code",
                    "lookup path: ${config.lookupPath}",
                    "access token: приет",
                ).joinToString("\n"),
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
