package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Durable profile-scoped cache of the last successful server history per phone.
 *
 * A network failure never writes here. A successful empty response is persisted and
 * therefore may authoritatively clear older cached rows the next time the phone is
 * opened. The cache survives process restarts and access-token renewal.
 */
internal object CallReportHistoryDiskCache {
    private const val PREFS = "relationship_manager_server_history_cache_v1"
    private const val KEY_SCOPE = "profile_scope"
    private const val KEY_ENTRIES = "entries_v1"
    private const val MAX_PHONE_ENTRIES = 600
    private const val MAX_EVENTS_PER_PHONE = 250
    private const val MAX_CACHE_AGE_MS = 180L * 24L * 60L * 60L * 1000L
    private val lock = Any()

    fun save(
        context: Context,
        config: AppConfig,
        phones: Collection<String>,
        result: CallReportHistoryLookupResult,
    ) {
        val appContext = context.applicationContext
        val scope = scopeFor(appContext, config) ?: return
        val requestedKeys = phones
            .mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        if (requestedKeys.isEmpty()) return

        synchronized(lock) {
            val now = System.currentTimeMillis()
            val updated = readLocked(appContext, scope).toMutableMap()
            requestedKeys.forEach { phoneKey ->
                val events = result.events
                    .asSequence()
                    .filter { event -> HomeCallPageLoader.noteKey(event.phone) == phoneKey }
                    .sortedByDescending(::changedAt)
                    .take(MAX_EVENTS_PER_PHONE)
                    .toList()
                updated[phoneKey] = CachedPhoneHistory(
                    savedAtMs = now,
                    principal = result.principal,
                    events = events,
                )
            }
            writeLocked(appContext, scope, trim(updated))
        }
    }

    /** Null means this profile has no cached result for any requested phone. */
    fun read(
        context: Context,
        config: AppConfig,
        phones: Collection<String>,
    ): CallReportHistoryLookupResult? {
        val appContext = context.applicationContext
        val scope = scopeFor(appContext, config) ?: return null
        val requestedKeys = phones
            .mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        if (requestedKeys.isEmpty()) return CallReportHistoryLookupResult()

        return synchronized(lock) {
            val now = System.currentTimeMillis()
            val stored = readLocked(appContext, scope)
            var found = false
            var principal = CallReportHistoryPrincipal()
            val events = mutableListOf<CallReportHistoryEvent>()
            val expiredKeys = linkedSetOf<String>()

            requestedKeys.forEach { phoneKey ->
                val entry = stored[phoneKey] ?: return@forEach
                if (entry.savedAtMs <= 0L || now - entry.savedAtMs > MAX_CACHE_AGE_MS) {
                    expiredKeys += phoneKey
                    return@forEach
                }
                found = true
                if (
                    principal.companies.isEmpty() && principal.brokerId.isBlank() && principal.brokerName.isBlank() &&
                    (entry.principal.companies.isNotEmpty() || entry.principal.brokerId.isNotBlank() || entry.principal.brokerName.isNotBlank())
                ) {
                    principal = entry.principal
                }
                events += entry.events
            }

            if (expiredKeys.isNotEmpty()) {
                writeLocked(appContext, scope, stored.filterKeys { it !in expiredKeys })
            }
            if (!found) return@synchronized null

            CallReportHistoryLookupResult(
                principal = principal,
                events = dedupe(events),
            )
        }
    }

    private fun dedupe(events: List<CallReportHistoryEvent>): List<CallReportHistoryEvent> {
        val latest = linkedMapOf<String, CallReportHistoryEvent>()
        events.forEach { event ->
            val key = event.clientEventId.trim()
                .ifBlank { event.serverId.trim() }
                .ifBlank {
                    listOf(
                        HomeCallPageLoader.noteKey(event.phone),
                        event.communicationType,
                        event.direction,
                        event.occurredAtMs.toString(),
                        event.companyId,
                        event.note.hashCode().toString(),
                    ).joinToString("|")
                }
            val current = latest[key]
            if (current == null || changedAt(event) >= changedAt(current)) latest[key] = event
        }
        return latest.values.sortedByDescending(::changedAt)
    }

    private fun trim(entries: Map<String, CachedPhoneHistory>): Map<String, CachedPhoneHistory> {
        if (entries.size <= MAX_PHONE_ENTRIES) return entries
        return entries.entries
            .sortedByDescending { it.value.savedAtMs }
            .take(MAX_PHONE_ENTRIES)
            .associateTo(linkedMapOf()) { it.toPair() }
    }

    private fun readLocked(context: Context, scope: String): Map<String, CachedPhoneHistory> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SCOPE, "").orEmpty() != scope) return emptyMap()
        val root = runCatching {
            JSONObject(prefs.getString(KEY_ENTRIES, "{}").orEmpty())
        }.getOrDefault(JSONObject())
        return buildMap {
            root.keys().forEach { phoneKey ->
                val item = root.optJSONObject(phoneKey) ?: return@forEach
                item.toCachedPhoneHistory()?.let { put(phoneKey, it) }
            }
        }
    }

    private fun writeLocked(
        context: Context,
        scope: String,
        entries: Map<String, CachedPhoneHistory>,
    ) {
        val root = JSONObject().apply {
            entries.forEach { (phoneKey, entry) -> put(phoneKey, entry.toJson()) }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCOPE, scope)
            .putString(KEY_ENTRIES, root.toString())
            .commit()
    }

    private fun CachedPhoneHistory.toJson(): JSONObject = JSONObject().apply {
        put("saved_at_ms", savedAtMs)
        put("principal", principal.toJson())
        put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
    }

    private fun JSONObject.toCachedPhoneHistory(): CachedPhoneHistory? {
        val savedAtMs = optLong("saved_at_ms", 0L)
        if (savedAtMs <= 0L) return null
        val principal = optJSONObject("principal")?.toPrincipal() ?: CallReportHistoryPrincipal()
        val events = buildList {
            val source = optJSONArray("events") ?: return@buildList
            for (index in 0 until source.length()) {
                source.optJSONObject(index)?.toHistoryEvent()?.let(::add)
            }
        }
        return CachedPhoneHistory(savedAtMs, principal, events)
    }

    private fun CallReportHistoryPrincipal.toJson(): JSONObject = JSONObject().apply {
        put("broker_id", brokerId)
        put("broker_name", brokerName)
        put("companies", JSONArray().apply {
            companies.forEach { company ->
                put(JSONObject().apply {
                    put("id", company.id)
                    put("name", company.name)
                })
            }
        })
    }

    private fun JSONObject.toPrincipal(): CallReportHistoryPrincipal = CallReportHistoryPrincipal(
        brokerId = optString("broker_id").trim(),
        brokerName = optString("broker_name").trim(),
        companies = buildList {
            val source = optJSONArray("companies") ?: return@buildList
            for (index in 0 until source.length()) {
                val company = source.optJSONObject(index) ?: continue
                val id = company.optString("id").trim()
                if (id.isBlank()) continue
                add(CallReportHistoryCompany(id, company.optString("name").trim().ifBlank { id }))
            }
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() },
    )

    private fun CallReportHistoryEvent.toJson(): JSONObject = JSONObject().apply {
        put("server_id", serverId)
        put("client_event_id", clientEventId)
        put("communication_type", communicationType)
        put("phone", phone)
        put("direction", direction)
        put("status", status)
        put("occurred_at_ms", occurredAtMs)
        put("duration_seconds", durationSeconds)
        put("note", note)
        put("contact_name", contactName)
        put("created_at_ms", createdAtMs)
        put("updated_at_ms", updatedAtMs)
        put("author_broker_id", authorBrokerId)
        put("author_broker_name", authorBrokerName)
        put("company_id", companyId)
    }

    private fun JSONObject.toHistoryEvent(): CallReportHistoryEvent? {
        val phone = optString("phone").trim()
        val occurredAtMs = optLong("occurred_at_ms", 0L)
        if (HomeCallPageLoader.noteKey(phone).isBlank() || occurredAtMs <= 0L) return null
        return CallReportHistoryEvent(
            serverId = optString("server_id").trim(),
            clientEventId = optString("client_event_id").trim(),
            communicationType = optString("communication_type", "phone").trim().ifBlank { "phone" },
            phone = phone,
            direction = optString("direction").trim(),
            status = optString("status").trim(),
            occurredAtMs = occurredAtMs,
            durationSeconds = optLong("duration_seconds", 0L).coerceAtLeast(0L),
            note = optString("note"),
            contactName = optString("contact_name").trim(),
            createdAtMs = optLong("created_at_ms", 0L),
            updatedAtMs = optLong("updated_at_ms", 0L),
            authorBrokerId = optString("author_broker_id").trim(),
            authorBrokerName = optString("author_broker_name").trim(),
            companyId = optString("company_id").trim(),
        )
    }

    private fun scopeFor(context: Context, config: AppConfig): String? {
        val baseUrl = config.baseUrl.trim().trimEnd('/').lowercase()
        if (baseUrl.isBlank()) return null
        val stableProfile = CompanySessionStore.profileScopeKey(context).ifBlank {
            CompanySessionStore.loadStored(context)?.let { snapshot ->
                snapshot.userEmail.trim().lowercase()
                    .ifBlank { PhoneNormalizer.key(snapshot.userPhone) }
            }.orEmpty()
        }.ifBlank { config.accessToken.trim() }
        if (stableProfile.isBlank()) return null
        return MessageDigest.getInstance("SHA-256")
            .digest("$baseUrl|$stableProfile".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun changedAt(event: CallReportHistoryEvent): Long =
        maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs)

    private data class CachedPhoneHistory(
        val savedAtMs: Long,
        val principal: CallReportHistoryPrincipal,
        val events: List<CallReportHistoryEvent>,
    )
}
