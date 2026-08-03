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
    val profileId: String = "",
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
    val authorProfileId: String = "",
    val isMine: Boolean? = null,
    val canEdit: Boolean? = null,
)

internal data class CallReportHistoryCompanyMainNote(
    val serverId: String = "",
    val clientEventId: String = "",
    val phone: String = "",
    val companyId: String = "",
    val companyName: String = "",
    val note: String = "",
    val updatedAtMs: Long = 0L,
)

internal data class CallReportHistoryLookupResult(
    val principal: CallReportHistoryPrincipal = CallReportHistoryPrincipal(),
    val events: List<CallReportHistoryEvent> = emptyList(),
    /** Dedicated server general notes returned outside history_items. */
    val companyMainNotes: List<CallReportHistoryCompanyMainNote> = emptyList(),
)

internal object CallReportHistoryLookupClient {
    private const val PATH = "/relationship-manager/history_lookup.php"
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
        // A non-null live result means at least one GET succeeded, including a valid
        // empty response. Only a total transport failure may fall back to disk.
        val live = lookupSinglePhoneVariantsOrNull(config, phone, limit, context)
        val result = if (live != null) {
            context?.let { CallReportHistoryDiskCache.save(it, config, listOf(phone), live) }
            live
        } else {
            context?.let { CallReportHistoryDiskCache.read(it, config, listOf(phone)) }
                ?: CallReportHistoryLookupResult()
        }
        updateGeneralNoteServerPresence(phone, result)
        return result
    }

    /** One request for Home, completed only where the batch omitted a phone's call notes. */
    fun lookupMany(config: AppConfig, phones: List<String>, context: Context? = null): CallReportHistoryLookupResult {
        return lookupManyOrNull(config, phones, context) ?: CallReportHistoryLookupResult()
    }

    /**
     * Same lookup as [lookupMany], but distinguishes a real successful empty response
     * from a total network failure. On total failure it returns the last successful
     * durable result when one exists, so Home and History never collapse offline.
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

        // A non-null batch is authoritative even when empty. If every live request
        // failed, read the last successful per-phone result without overwriting it.
        if (batch == null && singleResults.isEmpty()) {
            val cached = context?.let { CallReportHistoryDiskCache.read(it, config, originalPhones) }
            cached?.let { result ->
                originalPhones.forEach { phone -> updateGeneralNoteServerPresence(phone, result) }
            }
            return cached
        }

        val result = mergeResults(listOfNotNull(batch) + singleResults)
        context?.let { CallReportHistoryDiskCache.save(it, config, originalPhones, result) }
        originalPhones.forEach { phone -> updateGeneralNoteServerPresence(phone, result) }
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
            .firstOrNull { it.companies.isNotEmpty() || it.profileId.isNotBlank() || it.brokerId.isNotBlank() || it.brokerName.isNotBlank() }
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
        val seenMainNotes = linkedSetOf<String>()
        val companyMainNotes = results
            .flatMap { it.companyMainNotes }
            .filter { note ->
                val stableKey = note.serverId
                    .ifBlank { note.clientEventId }
                    .ifBlank {
                        listOf(
                            phoneKey(note.phone),
                            note.companyId,
                            note.note.hashCode().toString(),
                        ).joinToString("|")
                    }
                seenMainNotes.add(stableKey)
            }
            .sortedByDescending { it.updatedAtMs }
        return CallReportHistoryLookupResult(principal, events, companyMainNotes)
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
            buildEndpoint(config.baseUrl, PATH, linkedMapOf("phone" to singlePhone, "limit" to safeLimit.toString()))
        } else {
            buildEndpoint(config.baseUrl, PATH, linkedMapOf("limit" to safeLimit.toString()))
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
                return parsePayload(json).withLocalPrincipalFallback(context)
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

    private fun updateGeneralNoteServerPresence(phone: String, result: CallReportHistoryLookupResult) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        val foundInHistory = result.events.any { event ->
            phoneKey(event.phone) == key && CallReportServerNoteClassifier.isGeneralNote(event)
        }
        val foundInDedicatedItems = result.companyMainNotes.any { note ->
            phoneKey(note.phone) == key && note.note.trim().isNotBlank()
        }
        if (foundInHistory || foundInDedicatedItems) {
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

    internal fun parsePayload(json: JSONObject): CallReportHistoryLookupResult {
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
            brokerId = principalJson?.text("broker_id", "employee_id").orEmpty(),
            brokerName = principalJson?.text("broker_name", "display_name", "name").orEmpty(),
            companies = companies,
            profileId = principalJson?.text("profile_id", "user_id", "id").orEmpty(),
        )
        val companyNames = companies.associate { it.id to it.name }
        val companyMainNotes = buildList {
            val items = json.optJSONArray("company_main_note_items")
                ?: json.optJSONArray("company_main_notes_all")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    if (item.optBoolean("deleted", false)) continue
                    val phone = item.text("phone", "number")
                    val companyId = item.text("company_id")
                    val note = item.text("note", "notes", "text")
                    if (phone.isBlank() || companyId.isBlank() || note.isBlank()) continue
                    val updatedAt = item.numberMs("updated_at_ms", "updated_at", "occurred_at_ms", "occurred_at")
                    add(
                        CallReportHistoryCompanyMainNote(
                            serverId = item.text("id", "server_id", "note_id"),
                            clientEventId = item.text("client_event_id"),
                            phone = phone,
                            companyId = companyId,
                            companyName = item.text("company_name").ifBlank {
                                companyNames[companyId].orEmpty().ifBlank { companyId }
                            },
                            note = note,
                            updatedAtMs = updatedAt,
                        ),
                    )
                }
            }
        }.distinctBy { note ->
            note.serverId
                .ifBlank { note.clientEventId }
                .ifBlank { "${phoneKey(note.phone)}|${note.companyId}|${note.note.hashCode()}" }
        }.sortedByDescending { it.updatedAtMs }
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
                        authorBrokerId = item.text("author_broker_id", "created_by_broker_id", "note_author_broker_id", "author_employee_id", "author_id"),
                        authorBrokerName = item.text("author_name", "author_broker_name", "created_by_broker_name", "note_author_broker_name", "author"),
                        companyId = item.text("company_id"),
                        authorProfileId = item.text(
                            "author_profile_id",
                            "author_user_id",
                            "created_by_profile_id",
                            "created_by_user_id",
                            "note_author_profile_id",
                        ),
                        isMine = item.optionalBoolean("is_mine", "mine", "owned_by_current_user"),
                        canEdit = item.optionalBoolean("can_edit", "editable"),
                    )
                    if (event.phone.isNotBlank() && event.occurredAtMs > 0L) add(event)
                }
            }
        }
        return CallReportHistoryLookupResult(principal, events, companyMainNotes)
    }

    private fun CallReportHistoryLookupResult.withLocalPrincipalFallback(context: Context?): CallReportHistoryLookupResult {
        val session = context?.let { CompanySessionStore.load(it) } ?: return this
        val enriched = principal.copy(
            profileId = principal.profileId.ifBlank { session.userId },
            brokerName = principal.brokerName.ifBlank { session.userName },
        )
        return if (enriched == principal) this else copy(principal = enriched)
    }

    private fun JSONObject.optionalBoolean(vararg keys: String): Boolean? {
        keys.forEach { key ->
            if (!has(key) || isNull(key)) return@forEach
            return when (val value = opt(key)) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> when (value.trim().lowercase()) {
                    "1", "true", "yes", "y" -> true
                    "0", "false", "no", "n" -> false
                    else -> null
                }
                else -> null
            }
        }
        return null
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
