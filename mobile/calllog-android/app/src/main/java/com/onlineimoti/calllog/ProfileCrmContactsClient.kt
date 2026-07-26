package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Reads and updates only the signed-in profile's private CRM contact markers. */
internal object ProfileCrmContactsClient {
    private const val PATH = "/relationship-manager/profile_crm_contacts.php"

    data class Change(val phone: String, val active: Boolean)

    fun fetch(context: Context, config: AppConfig = ConfigStore.load(context)): Set<String> {
        return request(
            context = context.applicationContext,
            config = config,
            method = "GET",
            payload = null,
        )
    }

    fun update(
        context: Context,
        changes: List<Change>,
        config: AppConfig = ConfigStore.load(context),
    ): Set<String> {
        if (changes.isEmpty()) return fetch(context, config)
        val payload = JSONObject().put(
            "changes",
            JSONArray().apply {
                changes.take(500).forEach { change ->
                    put(JSONObject().put("phone", change.phone).put("active", change.active))
                }
            },
        )
        return request(
            context = context.applicationContext,
            config = config,
            method = "POST",
            payload = payload,
        )
    }

    private fun request(
        context: Context,
        config: AppConfig,
        method: String,
        payload: JSONObject?,
    ): Set<String> {
        require(CallReportRemoteAccess.isReady(config)) { "Първо влез в профила." }
        val endpoint = buildEndpoint(config.baseUrl, PATH, emptyMap())
        val connection = runCatching { URL(endpoint).openConnection() as HttpURLConnection }.getOrElse { error ->
            ServerConnectionNotifier.notifyFailure(context, config, error)
            throw error
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.accessToken}")
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
                connection.outputStream.use { it.write(bytes) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            if (status !in 200..299 || !json.optBoolean("ok", false)) {
                val message = json.optString("error").trim().ifBlank { "HTTP $status" }
                throw IllegalStateException(message)
            }
            return parsePhones(json)
        } catch (error: Throwable) {
            ServerConnectionNotifier.notifyFailure(context, config, error)
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePhones(json: JSONObject): Set<String> {
        val result = linkedSetOf<String>()
        val phones = json.optJSONArray("phones")
        for (index in 0 until (phones?.length() ?: 0)) {
            PhoneNormalizer.key(phones?.optString(index).orEmpty())
                .takeIf { it.isNotBlank() }
                ?.let(result::add)
        }
        val items = json.optJSONArray("contacts") ?: json.optJSONArray("items")
        for (index in 0 until (items?.length() ?: 0)) {
            val item = items?.optJSONObject(index) ?: continue
            if (item.has("active") && !item.optBoolean("active", false)) continue
            val phone = item.optString("normalized_phone").ifBlank { item.optString("phone") }
            PhoneNormalizer.key(phone).takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result
    }
}
