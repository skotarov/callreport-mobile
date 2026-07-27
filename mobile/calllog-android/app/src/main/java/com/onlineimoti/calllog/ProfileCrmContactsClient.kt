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

    data class Change(
        val phone: String,
        val active: Boolean,
        val updatedAtMs: Long = 0L,
    )

    data class Record(
        val phone: String,
        val active: Boolean,
        val updatedAtMs: Long,
    )

    data class Snapshot(
        val recordsByPhoneKey: Map<String, Record>,
        /** True only when the response explicitly contains inactive tombstones too. */
        val includesInactive: Boolean,
    ) {
        val activePhones: Set<String>
            get() = recordsByPhoneKey.values
                .asSequence()
                .filter { it.active }
                .map { PhoneNormalizer.key(it.phone) }
                .filter { it.isNotBlank() }
                .toCollection(linkedSetOf())
    }

    /** Backward-compatible active-phone API used by older callers. */
    fun fetch(context: Context, config: AppConfig = ConfigStore.load(context)): Set<String> =
        fetchSnapshot(context, config).activePhones

    fun fetchSnapshot(
        context: Context,
        config: AppConfig = ConfigStore.load(context),
    ): Snapshot = request(
        context = context.applicationContext,
        config = config,
        method = "GET",
        payload = null,
    )

    /** Backward-compatible active-phone API used by older callers. */
    fun update(
        context: Context,
        changes: List<Change>,
        config: AppConfig = ConfigStore.load(context),
    ): Set<String> = updateSnapshot(context, changes, config).activePhones

    fun updateSnapshot(
        context: Context,
        changes: List<Change>,
        config: AppConfig = ConfigStore.load(context),
    ): Snapshot {
        if (changes.isEmpty()) return fetchSnapshot(context, config)
        val payload = JSONObject().put(
            "changes",
            JSONArray().apply {
                changes.take(500).forEach { change ->
                    put(
                        JSONObject()
                            .put("phone", change.phone)
                            .put("active", change.active)
                            .put("updated_at_ms", change.updatedAtMs.coerceAtLeast(0L)),
                    )
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
    ): Snapshot {
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
                val message = json.optJSONObject("error")?.optString("message").orEmpty()
                    .ifBlank { json.optString("error").trim() }
                    .ifBlank { "HTTP $status" }
                throw IllegalStateException(message)
            }
            return parseSnapshot(json)
        } catch (error: Throwable) {
            ServerConnectionNotifier.notifyFailure(context, config, error)
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSnapshot(json: JSONObject): Snapshot {
        val result = linkedMapOf<String, Record>()
        val items = json.optJSONArray("contacts") ?: json.optJSONArray("items")
        for (index in 0 until (items?.length() ?: 0)) {
            val item = items?.optJSONObject(index) ?: continue
            val phone = item.optString("normalized_phone").ifBlank { item.optString("phone") }
            val key = PhoneNormalizer.key(phone)
            if (key.isBlank()) continue
            val record = Record(
                phone = phone.ifBlank { key },
                active = if (item.has("active")) item.optBoolean("active", false) else true,
                updatedAtMs = item.optLong("updated_at_ms", 0L).coerceAtLeast(0L),
            )
            val previous = result[key]
            if (previous == null || record.updatedAtMs >= previous.updatedAtMs) result[key] = record
        }

        // Older deployments return only a flat active-phone list. Keep it as a
        // complete active snapshot, but do not claim that inactive tombstones exist.
        val phones = json.optJSONArray("phones")
        for (index in 0 until (phones?.length() ?: 0)) {
            val phone = phones?.optString(index).orEmpty()
            val key = PhoneNormalizer.key(phone)
            if (key.isBlank() || result.containsKey(key)) continue
            result[key] = Record(phone = phone.ifBlank { key }, active = true, updatedAtMs = 0L)
        }

        val includesInactive = json.optBoolean("includes_inactive", false) ||
            json.optInt("sync_version", 1) >= 2
        return Snapshot(result, includesInactive)
    }
}
