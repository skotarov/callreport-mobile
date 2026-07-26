package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

internal data class CallReportHistoryCompany(
    val id: String,
    val name: String,
)

internal data class CallReportHistoryPrincipal(
    val brokerId: String = "",
    val brokerName: String = "",
    val companies: List<CallReportHistoryCompany> = emptyList(),
)

internal data class CallReportHistoryEvent(
    val serverId: String = "",
    val clientEventId: String = "",
    val communicationType: String = "phone",
    val phone: String = "",
    val direction: String = "",
    val status: String = "",
    val occurredAtMs: Long = 0L,
    val durationSeconds: Long = 0L,
    val note: String = "",
    val contactName: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val authorBrokerId: String = "",
    val authorBrokerName: String = "",
    val companyId: String = "",
)

internal data class CallReportHistoryLookupResult(
    val principal: CallReportHistoryPrincipal = CallReportHistoryPrincipal(),
    val events: List<CallReportHistoryEvent> = emptyList(),
)

internal object CallReportHistoryLookupClient {
    private const val DEFAULT_LIMIT = 200
    private const val MAX_LIMIT = 200
    private const val MAX_PHONE_VARIANTS = 50
    private const val MAX_SINGLE_FALLBACK_PHONES = 20
    private val generalNoteServerPhones = ConcurrentHashMap.newKeySet<String>()

    fun lookup(
        config: AppConfig,
        phone: String,
        limit: Int = DEFAULT_LIMIT,
        context: Context? = null,
    ): CallReportHistoryLookupResult {
        if (!isReady(config) || phone.isBlank()) return CallReportHistoryLookupResult()
        // Keep the single-contact History screen compatible with older server code:
        // it is known to work with GET ?phone=..., while POST/batch support may be absent.
        val result = lookupSinglePhoneVariantsOrNull(config, phone, limit, context)
            ?: CallReportHistoryLookupResult()
        updateGeneralNoteServerPresence(phone, result.events)
        return result
    }

    /** One request for Home, completed only where the batch omitted a phone's call notes. */
    fun lookupMany(config: AppConfig, phones: List<String>, context: Context? = null): CallReportHistoryLookupResult {
        return lookupManyOrNull(config, phones, context) ?: CallReportHistoryLookupResult()
    }

    /**
     * Same lookup as [lookupMany], but distinguishes a real successful empty response
     * from a total network failure. Home uses this to keep its last-known note cache
     * visible when the server is temporarily unreachable.
     */
    fun lookupManyOrNull(
        config: AppConfig,
        phones: List<String>,
        context: Context? = null,
    ): CallReportHistoryLookupResult? {
        if (!isReady(config)) return null
        val originalPhones = phones
            .map { it.trim() }
            .filter { phoneKey(it).isNotBlank() }
            .distinctBy(::phoneKey)
            .take(MAX_SINGLE_FALLBACK_PHONES)
        if (originalPhones.isEmpty()) return CallReportHistoryLookupResult()

        val requestedPhones = buildList {
            originalPhones.forEach { phone -> addAll(phoneCandidatesForLookup(phone)) }
        }
            .distinct()
            .filter { phoneKey(it).isNotBlank() }
            .take(MAX_PHONE_VARIANTS)

        val batch = runCatching { request(config, requestedPhones, DEFAULT_LIMIT, context) }.getOrNull()
        val fallbackPhones = phonesMissingNoteCoverage(originalPhones, batch?.events.orEmpty())
        val singleResults = fallbackPhones.mapNotNull { phone ->
            lookupSinglePhoneVariantsOrNull(config, phone, DEFAULT_LIMIT, context)
        }

        // A non-null batch is an authoritative successful response even when empty.
        // If the batch failed entirely, at least one successful single-phone GET is
        // required before an empty/partial result may replace Home's last-known cache.
        if (batch == null && singleResults.isEmpty()) return null
        val result = mergeResults(listOfNotNull(batch) + singleResults)
        originalPhones.forEach { phone -> updateGeneralNoteServerPresence(phone, result.events) }
        return result
    }

    /**
     * A yellow/general note or a phone record does not prove that the batch included
     * the blue notes attached to a concrete call. Only concrete call-note rows cover
     * the phone; otherwise Home completes it with the same GET used by History.
     */
    internal fun phonesMissingNoteCoverage(
        phones: List<String>,
        batchEvents: List<CallReportHistoryEvent>,
    ): List<String> {
        val coveredKeys = batchEvents.asSequence()
            .filter(CallReportServerNoteClassifier::isConcreteCallNote)
            .map { phoneKey(it.phone) }
            .filter { it.isNotBlank() }
            .toSet()
        return phones.filter { phone -> phoneKey(phone) !in coveredKeys }
    }

    /** Server presence of the main contact note, independent of this installation's client_event_id. */
    fun hasGeneralNoteOnServer(phone: String): Boolean {
        val key = phoneKey(phone)
        return key.isNotBlank() && key in generalNoteServerPhones
    }

    /** Called after a successful durable general-note sync, before a subsequent history refresh arrives. */
    fun markGeneralNoteOnServer(phone: String) {
        phoneKey(phone).takeIf { it.isNotBlank() }?.let { key ->
            generalNoteServerPhones.add(key)
        }
    }

    private fun lookupSinglePhoneVariantsOrNull(
        config: AppConfig,
        phone: String,
        limit: Int,
        context: Context? = null,
    ): CallReportHistoryLookupResult? {
        val variants = phoneCandidatesForLookup(phone).ifEmpty { listOf(phone) }
        val successful = variants.mapNotNull { variant ->
            runCatching { request(config, listOf(variant), limit, context) }.getOrNull()
        }
        if (successful.isEmpty()) return null
        return mergeResults(successful)
    }

    private fun mergeResults(results: List<CallReportHistoryLookupResult>): CallReportHistoryLookupResult {
        if (results.isEmpty()) return CallReportHistoryLookupResult()
        val principal = results
            .map { it.principal }
            .firstOrNull { it.companies.isNotEmpty() || it.brokerId.isNotBlank() || it.brokerName.isNotBlank() }
            ?: CallReportHistoryPrincipal()
        val seen = linkedSetOf<String>()
        val events = results.flatMap { it.events }.filter { event ->
            val stableKey = event.clientEventId
                .ifBlank { event.serverId }
                .ifBlank {
                    listOf(
                        phoneKey(event.phone),
                        event.communicationType,
                        event.direction,
                        event.occurredAtMs.toString(),
                        event.note.hashCode().toString(),
                    ).joinToString("|")
                }
            seen.add(stableKey)
        }.sortedByDescending { event -> maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs) }
        return CallReportHistoryLookupResult(principal, events)
    }

    private fun request(
        config: AppConfig,
        phones: List<String>,
        limit: Int,
        context: Context? = null,
    ): CallReportHistoryLookupResult {
        val safeLimit = limit.coerceIn(1, MAX_LIMIT)
        val singlePhone = phones.singleOrNull()
        val url = if (singlePhone != null) {
            buildEndpoint(config.baseUrl, config.historyLookupPath, linkedMapOf("phone" to singlePhone, "limit" to safeLimit.toString()))
        } else {
            buildEndpoint(config.baseUrl, config.historyLookupPath, linkedMapOf("limit" to safeLimit.toString()))
        }
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrElse { error ->
            ServerConnectionNotifier.notifyFailure(context, config, error)
            throw error
        }
        try {
            try {
                connection.requestMethod = if (singlePhone != null) "GET" else "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
                if (singlePhone == null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val payload = JSONObject().apply {
                        put("phones", JSONArray().apply { phones.forEach(::put) })
                    }.toString()
                    connection.outputStream.use { output ->
                        output.write(payload.toByteArray(Charsets.UTF_8))
                    }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
                if (status !in 200..299) throw IllegalStateException("HTTP $status")
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "History lookup failed"))
                return parse(json)
            } catch (error: Throwable) {
                ServerConnectionNotifier.notifyFailure(context, config, error)
                throw error
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isReady(config: AppConfig): Boolean =
        config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()

    private fun updateGeneralNoteServerPresence(phone: String, events: List<CallReportHistoryEvent>) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        if (events.any { event -> phoneKey(event.phone) == key && CallReportServerNoteClassifier.isGeneralNote(event) }) {
            generalNoteServerPhones.add(key)
        } else {
            generalNoteServerPhones.remove(key)
        }
    }

    private fun phoneCandidatesForLookup(phone: String): List<String> {
        val key = phoneKey(phone)
        if (key.isBlank()) return emptyList()
        return linkedSetOf<String>().apply {
            add(phone.trim())
            add(PhoneNormalizer.normalize(phone))
            addAll(PhoneNormalizer.candidates(phone))
        }.filter { it.isNotBlank() }
    }

    private fun phoneKey(phone: String): String = HomeCallPageLoader.noteKey(phone)

    private fun parse(json: JSONObject): CallReportHistoryLookupResult {
        val principalJson = json.optJSONObject("principal") ?: json.optJSONObject("authenticated_principal")
        val companies = buildList {
            val source = principalJson?.optJSONArray("companies")
            if (source != null) {
                for (index in 0 until source.length()) {
                    val item = source.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isBlank()) continue
                    add(CallReportHistoryCompany(id, item.optString("name").trim().ifBlank { id }))
                }
            }
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        val principal = CallReportHistoryPrincipal(
            brokerId = principalJson?.optString("broker_id").orEmpty().trim(),
            brokerName = principalJson?.optString("broker_name").orEmpty().trim(),
            companies = companies,
        )
        val events = buildList {
            val items = json.optJSONArray("history_items") ?: json.optJSONArray("items")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val occurredAt = item.numberMs("occurred_at_ms", "timestamp", "date")
                    val updatedAt = item.numberMs("updated_at_ms", "updated_at")
                    val createdAt = item.numberMs("created_at_ms", "created_at")
                    val event = CallReportHistoryEvent(
                        serverId = item.text("id", "server_id"),
                        clientEventId = item.text("client_event_id"),
                        communicationType = item.text("communication_type", "type").ifBlank { "phone" },
                        phone = item.text("phone", "number"),
                        direction = item.text("direction"),
                        status = item.text("status"),
                        occurredAtMs = occurredAt.takeIf { it > 0L } ?: maxOf(updatedAt, createdAt),
                        durationSeconds = item.number("duration_seconds", "duration"),
                        note = item.text("note", "notes", "text"),
                        contactName = item.text("contact_name", "contact"),
                        createdAtMs = createdAt,
                        updatedAtMs = updatedAt,
                        authorBrokerId = item.text("author_broker_id", "created_by_broker_id", "note_author_broker_id"),
                        authorBrokerName = item.text("author_broker_name", "created_by_broker_name", "note_author_broker_name", "author"),
                        companyId = item.text("company_id"),
                    )
                    if (event.phone.isNotBlank() && event.occurredAtMs > 0L) add(event)
                }
            }
        }
        return CallReportHistoryLookupResult(principal, events)
    }

    private fun JSONObject.text(vararg keys: String): String {
        keys.forEach { key ->
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun JSONObject.number(vararg keys: String): Long {
        keys.forEach { key ->
            val value = opt(key)
            when (value) {
                is Number -> if (value.toLong() > 0L) return value.toLong()
                is String -> value.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
            }
        }
        return 0L
    }

    private fun JSONObject.numberMs(vararg keys: String): Long {
        val raw = number(*keys)
        if (raw <= 0L) return 0L
        return if (raw < 100_000_000_000L) raw * 1000L else raw
    }
}
