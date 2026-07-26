package com.onlineimoti.calllog

import org.json.JSONArray
import org.json.JSONObject

internal object CallReportCompanyGeneralNotesParser {
    fun parse(
        context: android.content.Context,
        phone: String,
        response: JSONObject,
    ): List<CallReportCompanyMainNote> {
        val principal = response.optJSONObject("principal") ?: response.optJSONObject("authenticated_principal")
        val principalId = principal?.optString("broker_id").orEmpty().trim()
        val principalName = principal?.optString("broker_name").orEmpty().trim()
        val companies = companyMap(response, principal)
        val dedicatedItems = response.optJSONArray("company_main_note_items")
            ?: response.optJSONArray("company_main_notes_all")
        val capabilities = response.optJSONObject("capabilities")
        val multiAuthor = dedicatedItems != null ||
            response.optBoolean("company_main_notes_multi_author", false) ||
            capabilities?.optBoolean("company_main_notes_multi_author", false) == true

        return if (multiAuthor) {
            parseMultiAuthor(
                context = context,
                phone = phone,
                companies = companies,
                items = dedicatedItems ?: historyItems(response),
                dedicatedItems = dedicatedItems != null,
                principalId = principalId,
                principalName = principalName,
            )
        } else {
            parseLegacy(context, phone, response, companies)
        }
    }

    private fun companyMap(response: JSONObject, principal: JSONObject?): LinkedHashMap<String, String> {
        val companies = linkedMapOf<String, String>()
        principal?.optJSONArray("companies")?.let { source ->
            for (index in 0 until source.length()) {
                val company = source.optJSONObject(index) ?: continue
                val id = company.optString("id").trim()
                if (id.isNotBlank()) companies[id] = company.optString("name").trim().ifBlank { id }
            }
        }
        if (companies.isEmpty()) {
            response.optJSONObject("company")?.let { company ->
                val id = company.optString("id").trim()
                if (id.isNotBlank()) companies[id] = company.optString("name").trim().ifBlank { id }
            }
        }
        if (companies.isEmpty()) {
            response.optJSONObject("account")?.let { account ->
                val id = account.optString("id").trim()
                if (id.isNotBlank()) companies[id] = account.optString("name").trim().ifBlank { id }
            }
        }
        return companies
    }

    private fun parseMultiAuthor(
        context: android.content.Context,
        phone: String,
        companies: LinkedHashMap<String, String>,
        items: JSONArray?,
        dedicatedItems: Boolean,
        principalId: String,
        principalName: String,
    ): List<CallReportCompanyMainNote> {
        val requestedPhoneKey = phoneKey(phone)
        val remoteByKey = linkedMapOf<String, CallReportCompanyMainNote>()
        if (items != null) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val clientEventId = item.optString("client_event_id").trim()
                if (!dedicatedItems && !isExplicitGeneralNoteId(clientEventId)) continue
                if (phoneKey(item.optString("phone")) != requestedPhoneKey) continue
                if (item.optBoolean("deleted", false)) continue

                val companyId = item.optString("company_id").trim()
                if (companyId.isBlank()) continue
                val companyName = item.optString("company_name").trim()
                    .ifBlank { companies[companyId].orEmpty().ifBlank { companyId } }
                companies.putIfAbsent(companyId, companyName)
                val note = item.optString("note").trim()
                if (note.isBlank()) continue
                val authorId = item.text("author_profile_id", "author_broker_id", "created_by_broker_id")
                val authorName = item.text("author_name", "author_broker_name", "created_by_broker_name", "author")
                val updatedAt = item.optLong("updated_at_ms", item.optLong("occurred_at_ms", 0L))
                val candidate = CallReportCompanyMainNote(
                    companyId = companyId,
                    companyName = companyName,
                    note = note,
                    updatedAtMs = updatedAt,
                    confirmedByServer = true,
                    pending = false,
                    clientEventId = clientEventId,
                    authorBrokerId = authorId,
                    authorBrokerName = authorName,
                    editable = canEdit(item, authorId, authorName, principalId, principalName),
                    multiAuthor = true,
                )
                val stableKey = item.text("id", "server_id", "note_id")
                    .ifBlank { clientEventId }
                    .ifBlank { "$companyId|$authorId|${authorName.lowercase()}|${note.hashCode()}" }
                val current = remoteByKey[stableKey]
                if (current == null || candidate.updatedAtMs >= current.updatedAtMs) remoteByKey[stableKey] = candidate
            }
        }

        val result = mutableListOf<CallReportCompanyMainNote>()
        val companyIds = linkedSetOf<String>().apply {
            addAll(companies.keys)
            addAll(remoteByKey.values.map { it.companyId })
        }
        companyIds.forEach { companyId ->
            val companyName = companies[companyId].orEmpty().ifBlank { companyId }
            val companyNotes = remoteByKey.values
                .filter { it.companyId == companyId }
                .sortedByDescending { it.updatedAtMs }
                .toMutableList()
            val pending = CallReportCompanyGeneralNotePending.isPending(context, phone, companyId)
            val cached = CallReportCompanyGeneralNoteStore.noteFor(context, phone, companyId)
            if (pending) {
                companyNotes.removeAll { it.editable }
                companyNotes += CallReportCompanyMainNote(
                    companyId = companyId,
                    companyName = companyName,
                    note = cached,
                    updatedAtMs = System.currentTimeMillis(),
                    confirmedByServer = false,
                    pending = true,
                    authorBrokerId = principalId,
                    authorBrokerName = principalName,
                    editable = true,
                    multiAuthor = true,
                )
            }
            if (companyNotes.none { it.editable }) {
                companyNotes += CallReportCompanyMainNote(
                    companyId = companyId,
                    companyName = companyName,
                    note = "",
                    updatedAtMs = 0L,
                    confirmedByServer = false,
                    pending = false,
                    authorBrokerId = principalId,
                    authorBrokerName = principalName,
                    editable = true,
                    multiAuthor = true,
                    placeholder = true,
                )
            }
            result += companyNotes
        }
        return result.sortedWith(
            compareBy<CallReportCompanyMainNote> { it.companyName.lowercase() }
                .thenByDescending { it.updatedAtMs }
        )
    }

    private fun parseLegacy(
        context: android.content.Context,
        phone: String,
        response: JSONObject,
        companies: LinkedHashMap<String, String>,
    ): List<CallReportCompanyMainNote> {
        val latestByCompany = mutableMapOf<String, RemoteNote>()
        val requestedPhoneKey = phoneKey(phone)
        val items = historyItems(response)
        if (items != null) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val clientId = item.optString("client_event_id").trim()
                if (!isExplicitGeneralNoteId(clientId)) continue
                if (phoneKey(item.optString("phone")) != requestedPhoneKey) continue

                val companyId = item.optString("company_id").trim()
                if (companyId.isBlank()) continue
                val companyName = item.optString("company_name").trim()
                    .ifBlank { companies[companyId].orEmpty().ifBlank { companyId } }
                companies.putIfAbsent(companyId, companyName)
                val updatedAt = item.optLong("updated_at_ms", item.optLong("occurred_at_ms", 0L))
                val candidate = RemoteNote(
                    note = item.optString("note").trim(),
                    updatedAtMs = updatedAt,
                    clientEventId = clientId,
                    authorBrokerId = item.text("author_profile_id", "author_broker_id"),
                    authorBrokerName = item.text("author_name", "author_broker_name", "author"),
                )
                val current = latestByCompany[companyId]
                if (current == null || candidate.updatedAtMs >= current.updatedAtMs) latestByCompany[companyId] = candidate
            }
        }

        return companies.entries.map { (companyId, companyName) ->
            val remote = latestByCompany[companyId]
            val cached = CallReportCompanyGeneralNoteStore.noteFor(context, phone, companyId)
            val pending = CallReportCompanyGeneralNotePending.isPending(context, phone, companyId)
            val note = when {
                pending && cached.isNotBlank() -> cached
                remote != null -> remote.note
                else -> cached
            }
            CallReportCompanyMainNote(
                companyId = companyId,
                companyName = companyName,
                note = note,
                updatedAtMs = remote?.updatedAtMs ?: 0L,
                confirmedByServer = remote != null && !pending && remote.note.isNotBlank(),
                pending = pending,
                clientEventId = remote?.clientEventId.orEmpty(),
                authorBrokerId = remote?.authorBrokerId.orEmpty(),
                authorBrokerName = remote?.authorBrokerName.orEmpty(),
            )
        }.sortedBy { it.companyName.lowercase() }
    }

    private fun historyItems(response: JSONObject): JSONArray? =
        response.optJSONArray("history_items") ?: response.optJSONArray("items")

    private fun canEdit(
        item: JSONObject,
        authorId: String,
        authorName: String,
        principalId: String,
        principalName: String,
    ): Boolean {
        if (item.has("can_edit")) return item.optBoolean("can_edit", false)
        if (item.has("is_mine")) return item.optBoolean("is_mine", false)
        if (authorId.isNotBlank() && principalId.isNotBlank()) return authorId == principalId
        if (authorName.isNotBlank() && principalName.isNotBlank()) {
            return authorName.equals(principalName, ignoreCase = true)
        }
        return false
    }

    private fun isExplicitGeneralNoteId(clientEventId: String): Boolean =
        clientEventId.contains(":topic:general:") || clientEventId.contains(":note:general:")

    private data class RemoteNote(
        val note: String,
        val updatedAtMs: Long,
        val clientEventId: String,
        val authorBrokerId: String,
        val authorBrokerName: String,
    )

    private fun JSONObject.text(vararg keys: String): String {
        keys.forEach { key ->
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun phoneKey(value: String): String {
        val digits = value.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
