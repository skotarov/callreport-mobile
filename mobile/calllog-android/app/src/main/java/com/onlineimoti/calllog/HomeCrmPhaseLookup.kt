package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal object HomeCrmPhaseLookup {
    private const val MAX_PHONES = 50
    private const val MAX_PARALLEL = 4
    private const val MAX_CACHE = 5_000
    private val cache = ConcurrentHashMap<String, Map<String, Int>>()

    fun invalidate() = cache.clear()

    fun resolveCompanyPhases(config: AppConfig, phones: List<String>, context: Context? = null): Map<String, Map<String, Int>> {
        if (!CallReportRemoteAccess.isReady(config)) return emptyMap()
        val requested = linkedMapOf<String, String>()
        phones.forEach { phone ->
            HomeCallPageLoader.noteKey(phone).takeIf { it.isNotBlank() }?.let { requested.putIfAbsent(it, phone) }
        }
        if (requested.isEmpty()) return emptyMap()
        val scope = "${config.baseUrl.trim().trimEnd('/')}|${config.accessToken.hashCode()}"
        val missing = requested.filterKeys { cache["$scope|$it"] == null }.values.toList()
        if (missing.isNotEmpty()) fetchMissing(scope, config, missing, context)
        return requested.keys.mapNotNull { key -> cache["$scope|$key"]?.let { key to it } }.toMap()
    }

    /**
     * Phase filtering must also see a just-edited local phase before its server
     * round-trip completes. The ordinary batch lookup remains canonical for
     * server data; local company state fills only absent server entries.
     */
    fun resolveEffectiveCompanyPhases(
        context: Context,
        config: AppConfig,
        phones: List<String>,
    ): Map<String, Map<String, Int>> {
        val requested = linkedMapOf<String, String>()
        phones.forEach { phone ->
            HomeCallPageLoader.noteKey(phone).takeIf { it.isNotBlank() }?.let { requested.putIfAbsent(it, phone) }
        }
        if (requested.isEmpty()) return emptyMap()

        // A temporary endpoint/network issue should not discard locally assigned
        // phases and turn the selected phase filter into an empty list.
        val remote = runCatching { resolveCompanyPhases(config, requested.values.toList(), context.applicationContext) }
            .onFailure { error -> ServerConnectionNotifier.notifyFailure(context.applicationContext, config, error) }
            .getOrDefault(emptyMap())
        val result = requested.keys.associateWithTo(linkedMapOf()) { phoneKey ->
            remote[phoneKey].orEmpty().toMutableMap()
        }

        val companyIds = linkedSetOf<String>()
        remote.values.forEach { phases -> companyIds += phases.keys.filter { it.isNotBlank() } }
        val cachedCompanies = CallReportTopicCompaniesCache
            .read(context.applicationContext, config)
            ?.companies
            .orEmpty()
        companyIds += cachedCompanies.map { it.id.trim() }.filter { it.isNotBlank() }
        if (companyIds.isEmpty() && CallReportRemoteAccess.isReady(config)) {
            val loadedCompanies = runCatching {
                CallReportTopicCompaniesRepository.load(context.applicationContext, config).companies
            }.onFailure { error ->
                ServerConnectionNotifier.notifyFailure(context.applicationContext, config, error)
            }.getOrDefault(emptyList())
            companyIds += loadedCompanies.map { it.id.trim() }.filter { it.isNotBlank() }
        }
        if (companyIds.isEmpty()) return result.filterValues { it.isNotEmpty() }

        requested.forEach { (phoneKey, rawPhone) ->
            val phases = result.getValue(phoneKey)
            companyIds.forEach companyLoop@{ companyId ->
                val hasLocalCompanyState = CompanyNegotiationPhaseStore.hasSavedState(
                    context,
                    rawPhone,
                    companyId,
                )
                if (hasLocalCompanyState) {
                    val localPhase = CompanyNegotiationPhaseStore.state(context, rawPhone, companyId).phase
                    if (localPhase in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4) {
                        phases[companyId] = localPhase
                    } else {
                        phases.remove(companyId)
                    }
                    return@companyLoop
                }

                // Retain the old phone-wide phase as a fallback only where the
                // server did not already return a company-scoped phase.
                if (companyId !in phases) {
                    val legacyPhase = ContactNegotiationPhaseStore.state(context, rawPhone).phase
                    if (legacyPhase in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4) {
                        phases[companyId] = legacyPhase
                    }
                }
            }
        }
        return result.filterValues { it.isNotEmpty() }
    }

    private fun fetchMissing(scope: String, config: AppConfig, phones: List<String>, context: Context? = null) {
        if (cache.size > MAX_CACHE) cache.clear()
        val batches = phones.chunked(MAX_PHONES)
        val executor = Executors.newFixedThreadPool(minOf(MAX_PARALLEL, batches.size))
        try {
            batches.map { batch -> executor.submit(Callable { CompanyNegotiationPhaseBatchRemoteClient.fetch(config, batch, context) }) }
                .forEach { future -> future.get().forEach { (phone, phases) -> cache["$scope|$phone"] = phases } }
        } finally {
            executor.shutdownNow()
        }
    }
}

internal object CompanyNegotiationPhaseBatchRemoteClient {
    fun fetch(config: AppConfig, phones: List<String>, context: Context? = null): Map<String, Map<String, Int>> {
        val requested = linkedMapOf<String, String>()
        phones.forEach { phone ->
            HomeCallPageLoader.noteKey(phone).takeIf { it.isNotBlank() }?.let { requested.putIfAbsent(it, phone) }
        }
        if (requested.isEmpty()) return emptyMap()
        val connection = runCatching {
            URL(config.baseUrl.trim().trimEnd('/') + config.companyPhasePath).openConnection() as HttpURLConnection
        }.getOrElse { error ->
            ServerConnectionNotifier.notifyFailure(context, config, error)
            throw error
        }
        try {
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // The single-record phase client sends both headers. The batch lookup
                // must authenticate identically, otherwise selecting a phase silently
                // yields no CRM rows on installations still validating Callreport auth.
                connection.setRequestProperty("X-Callreport-Token", config.accessToken)
                connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
                val payload = JSONObject().put("phones", JSONArray().apply { requested.values.forEach(::put) }).toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (status !in 200..299 || json?.optBoolean("ok") != true) {
                    throw IOException(json?.optString("error").orEmpty().ifBlank { "CRM phase request failed (HTTP $status)." })
                }
                val result = requested.keys.associateWith { linkedMapOf<String, Int>() }.toMutableMap()
                val items = json.optJSONArray("items")
                for (i in 0 until (items?.length() ?: 0)) {
                    val item = items?.optJSONObject(i) ?: continue
                    val phoneKey = HomeCallPageLoader.noteKey(item.optString("phone"))
                    val companyId = item.optString("company_id").trim()
                    if (phoneKey in result && companyId.isNotBlank()) result.getValue(phoneKey)[companyId] = item.optInt("phase", 0)
                }
                return result.mapValues { it.value.toMap() }
            } catch (error: Throwable) {
                ServerConnectionNotifier.notifyFailure(context, config, error)
                throw error
            }
        } finally {
            connection.disconnect()
        }
    }
}
