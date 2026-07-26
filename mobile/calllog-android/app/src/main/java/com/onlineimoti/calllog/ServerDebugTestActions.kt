package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit debug probes for the deployed Relationship Manager server contract.
 * Read-only probes never write. Sync and submit are opt-in because they intentionally
 * create/update a clearly marked deterministic test event for the configured test phone.
 */
internal data class ServerDebugTestResult(
    val label: String,
    val success: Boolean,
    val detail: String,
)

internal object ServerDebugTestActions {
    private data class WriteTestCompany(
        val id: String,
        val name: String,
    )

    fun runAll(
        context: Context,
        config: AppConfig,
        phone: String,
        direction: String,
        allowWriteTests: Boolean,
    ): List<ServerDebugTestResult> {
        val validation = validate(config, phone)
        if (validation != null) return listOf(validation)

        return buildList {
            add(testConfig(config))
            add(testLookup(config, phone, direction))
            add(testNotesLookup(config, phone))
            add(testHistoryLookup(config, phone, direction))
            add(testPropertySearch(config, phone))
            add(testFormPage(config, phone, direction))
            add(testHistoryPage(config, phone))
            if (allowWriteTests) {
                add(testSync(context, config, phone, direction))
                add(testSubmit(context, config, phone, direction))
            } else {
                add(ServerDebugTestResult("sync.php", true, "пропуснат — тестовите записи не са разрешени"))
                add(ServerDebugTestResult("submit.php", true, "пропуснат — тестовите записи не са разрешени"))
            }
        }
    }

    fun testConfig(config: AppConfig): ServerDebugTestResult =
        ServerDebugProbeClient.jsonGet(config, "config.php", config.configPath)

    fun testLookup(config: AppConfig, phone: String, direction: String): ServerDebugTestResult {
        return ServerDebugProbeClient.jsonGet(
            config = config,
            label = "lookup.php",
            path = config.standaloneLookupPath,
            params = lookupContext(config, phone, direction, "lookup"),
        )
    }

    fun testNotesLookup(config: AppConfig, phone: String): ServerDebugTestResult {
        return ServerDebugProbeClient.jsonPost(
            config = config,
            label = "home_notes.php",
            path = config.homeNotesPath,
            body = JSONObject().put("phones", JSONArray().put(phone)),
        )
    }

    fun testHistoryLookup(config: AppConfig, phone: String, direction: String): ServerDebugTestResult {
        return ServerDebugProbeClient.jsonGet(
            config = config,
            label = "history_lookup.php",
            path = config.historyLookupPath,
            params = linkedMapOf("phone" to phone, "direction" to direction),
        )
    }

    fun testPropertySearch(config: AppConfig, query: String): ServerDebugTestResult {
        return ServerDebugProbeClient.jsonGet(
            config = config,
            label = "property_search.php",
            path = config.propertySearchPath,
            params = linkedMapOf("q" to query),
        )
    }

    fun testFormPage(config: AppConfig, phone: String, direction: String): ServerDebugTestResult {
        return ServerDebugProbeClient.htmlGet(
            config = config,
            label = "form.php",
            path = config.standaloneFormPath,
            params = formContext(phone, direction),
        )
    }

    fun testHistoryPage(config: AppConfig, phone: String): ServerDebugTestResult {
        return ServerDebugProbeClient.htmlGet(
            config = config,
            label = "history.php",
            path = config.standaloneHistoryPath,
            params = linkedMapOf("phone" to phone),
        )
    }

    fun testSync(
        context: Context,
        config: AppConfig,
        phone: String,
        direction: String,
    ): ServerDebugTestResult {
        return ServerDebugProbeClient.runProbe("sync.php (тестов запис)") {
            val company = resolveWriteTestCompany(config, phone, direction)
            val clientEventId = debugEventId(CallReportInstallationId.get(context), "sync")
            val event = JSONObject().apply {
                put("client_event_id", clientEventId)
                put("company_id", company.id)
                put("communication_type", "phone")
                put("direction", direction)
                put("status", "answered")
                put("phone", phone)
                put("contact_name", "Android debug test")
                put("occurred_at_ms", System.currentTimeMillis())
                put("duration_seconds", 0)
                put("source", JSONObject().apply {
                    put("channel", "android_debug")
                    put("device_id", CallReportInstallationId.get(context))
                    put("provider_row_id", "debug-sync")
                    put("app_version", BuildConfig.VERSION_NAME)
                })
            }
            val response = ServerDebugProbeClient.request(
                config = config,
                method = "POST",
                path = config.syncPath,
                jsonBody = JSONObject().apply {
                    put("schema_version", 1)
                    put("events", JSONArray().put(event))
                }.toString(),
            )
            val json = JSONObject(response.body)
            require(json.optBoolean("ok", false)) { json.optString("error", "ok=false") }
            "HTTP ${response.code} · записът е потвърден · ${company.name}"
        }
    }

    fun testSubmit(
        context: Context,
        config: AppConfig,
        phone: String,
        direction: String,
    ): ServerDebugTestResult {
        return ServerDebugProbeClient.runProbe("submit.php (тестова бележка)") {
            val company = resolveWriteTestCompany(config, phone, direction)
            val clientEventId = debugEventId(CallReportInstallationId.get(context), "submit")
            val params = linkedMapOf(
                "format" to "json",
                "company_id" to company.id,
                "phone" to phone,
                "direction" to direction,
                "communication_type" to "phone",
                "status" to "answered",
                "contact_name" to "Android debug test",
                "call_at" to System.currentTimeMillis().toString(),
                "duration" to "0",
                "client_event_id" to clientEventId,
                "notes" to "[TEST] Запис от Debug › Тест на сървъра. Може да се изтрие.",
            )
            val response = ServerDebugProbeClient.formRequest(config, config.submitPath, params)
            val json = JSONObject(response.body)
            require(json.optBoolean("ok", false)) { json.optString("error", "ok=false") }
            "HTTP ${response.code} · записът е потвърден · ${company.name}"
        }
    }

    fun buildFormUrl(context: Context, config: AppConfig, phone: String, direction: String): String {
        val params = formContext(phone, direction).toMutableMap()
        params["access_token"] = config.accessToken
        params["client_event_id"] = debugEventId(CallReportInstallationId.get(context), "form")
        return buildEndpoint(config.baseUrl, config.standaloneFormPath, params)
    }

    fun buildHistoryUrl(config: AppConfig, phone: String): String {
        return buildEndpoint(
            config.baseUrl,
            config.standaloneHistoryPath,
            linkedMapOf("phone" to phone, "access_token" to config.accessToken),
        )
    }

    private fun validate(config: AppConfig, phone: String): ServerDebugTestResult? {
        return when {
            !config.remoteEnabled -> ServerDebugTestResult("Сървър", false, "натисни „Свържи“ в настройките")
            config.baseUrl.isBlank() -> ServerDebugTestResult("Сървър", false, "липсва Base URL")
            config.accessToken.isBlank() -> ServerDebugTestResult("Сървър", false, "липсва Access token")
            phone.isBlank() -> ServerDebugTestResult("Сървър", false, "липсва тестов телефон")
            else -> null
        }
    }

    private fun lookupContext(
        config: AppConfig,
        phone: String,
        direction: String,
        operation: String,
    ): LinkedHashMap<String, String> = linkedMapOf(
        "phone" to phone,
        "direction" to direction,
        "history_limit" to "3",
        "communication_type" to "phone",
        "status" to "answered",
        "contact_name" to "Android server test",
        "call_at" to System.currentTimeMillis().toString(),
        "duration" to "0",
        "client_event_id" to debugEventId(contextId(config), operation),
    )

    private fun resolveWriteTestCompany(
        config: AppConfig,
        phone: String,
        direction: String,
    ): WriteTestCompany {
        val response = ServerDebugProbeClient.request(
            config = config,
            method = "GET",
            path = config.standaloneLookupPath,
            params = lookupContext(config, phone, direction, "write-company"),
        )
        val json = JSONObject(response.body)
        require(json.optBoolean("ok", false)) {
            json.optString("error", "lookup.php върна ok=false")
        }

        val primaryCompany = json.optJSONObject("company")
        val primaryId = primaryCompany?.optString("id").orEmpty().trim()
        if (primaryId.isNotBlank()) {
            return WriteTestCompany(
                id = primaryId,
                name = primaryCompany?.optString("name").orEmpty().trim().ifBlank { primaryId },
            )
        }

        val companies = json.optJSONArray("companies")
        for (index in 0 until (companies?.length() ?: 0)) {
            val company = companies?.optJSONObject(index) ?: continue
            val id = company.optString("id").trim()
            if (id.isNotBlank()) {
                return WriteTestCompany(
                    id = id,
                    name = company.optString("name").trim().ifBlank { id },
                )
            }
        }

        throw IllegalStateException("lookup.php не върна достъпна фирма за тестовия запис.")
    }

    private fun formContext(phone: String, direction: String): LinkedHashMap<String, String> =
        linkedMapOf(
            "phone" to phone,
            "direction" to direction,
            "communication_type" to "phone",
            "status" to "answered",
            "contact_name" to "Android debug test",
            "call_at" to System.currentTimeMillis().toString(),
            "duration" to "0",
        )

    private fun debugEventId(deviceId: String, operation: String): String =
        "$deviceId:debug-server-test:$operation"

    private fun contextId(config: AppConfig): String =
        config.baseUrl.trim().ifBlank { "server" }.hashCode().toString()
}
