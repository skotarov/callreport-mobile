package com.onlineimoti.calllog

import android.content.Context

internal object HomeCrmContactCandidatesServer {
    private const val FALLBACK_CHUNK_SIZE = 100
    private const val FALLBACK_MAX_ITEMS = 2_000

    fun load(
        context: Context,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
    ): List<PhoneCallRecord> = loadPage(
        context = context,
        filterState = filterState,
        searchQuery = searchQuery,
        limit = ServerCrmContactsClient.DEFAULT_PAGE_SIZE,
        offset = 0,
    ).calls

    fun loadPage(
        context: Context,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
        limit: Int,
        offset: Int,
    ): ServerCrmContactsPage {
        val appContext = context.applicationContext
        // Older builds keyed private CRM markers by the profile email/phone.
        // Recover those proven aliases before synchronizing the current userId scope.
        CrmContactProfileScopeMigration.migrateKnownAliases(appContext)
        // Keep the existing LWW CRM store canonical. Its pending local edits survive
        // offline operation and are reconciled before the Clients request is made.
        CrmContactSyncStore.refreshFromServer(appContext)
        val config = ConfigStore.load(appContext)
        val primary = ServerCrmContactsClient.lookupPage(
            config = config,
            filterState = filterState,
            searchQuery = searchQuery,
            limit = limit,
            offset = offset,
            context = appContext,
        )

        // A company-scoped request is already the compatibility path that works on
        // mixed/older server deployments. Never replace a legitimate empty page on it.
        if (filterState.hasCompanyFilter || primary.clients.isNotEmpty() || primary.total > 0) {
            return primary
        }

        // Some live deployments still return an empty unscoped Clients response while
        // the same data is available as soon as company_id is supplied. Reconstruct the
        // unscoped universe from those working scopes. This fallback is used only after
        // a successful but empty primary response, so the normal authoritative contract
        // remains preferred and automatically takes over once production is updated.
        return loadCompatibilityPage(
            context = appContext,
            config = config,
            filterState = filterState,
            searchQuery = searchQuery,
            limit = limit,
            offset = offset,
        ) ?: primary
    }

    private fun loadCompatibilityPage(
        context: Context,
        config: AppConfig,
        filterState: HomeCrmFilterState,
        searchQuery: String,
        limit: Int,
        offset: Int,
    ): ServerCrmContactsPage? {
        val companies = runCatching {
            CallReportTopicCompaniesRepository.load(context, config).companies
        }.getOrDefault(emptyList())
        val companyIds = companies.map { it.id.trim() }.filter { it.isNotBlank() }.distinct().toSet()

        // CRM is personal state, not a company filter. Ask the old server for the broad
        // client universe and apply the already-synchronized profile CRM markers locally.
        val broadState = filterState.copy(crmOnly = false)
        val merged = linkedMapOf<String, ServerCrmClient>()

        if (companyIds.isNotEmpty()) {
            collectScope(
                context = context,
                config = config,
                filterState = broadState.copy(companyIds = companyIds),
                searchQuery = searchQuery,
                destination = merged,
            )
        }

        // "none" is the server's backward-compatible spelling for personal/unassigned
        // records. It is intentionally sent only inside this fallback path.
        collectScope(
            context = context,
            config = config,
            filterState = broadState.copy(companyIds = setOf("none")),
            searchQuery = searchQuery,
            destination = merged,
        )

        // An active personal CRM marker is itself server-owned relationship state. Add a
        // lightweight row when an older Clients endpoint cannot materialize that number.
        // Bare markers have no trustworthy phase, so they are not injected under a phase filter.
        if (!filterState.hasPhaseFilter) {
            CrmContactSyncStore.activeRecords(context).forEach { marker ->
                if (!matchesSearch(context, marker.phone, searchQuery)) return@forEach
                val key = clientKey(marker.phone)
                if (key.isBlank()) return@forEach
                val existing = merged[key]
                if (existing == null) {
                    val localName = ContactGroupFilter.resolveDisplayName(context, marker.phone).orEmpty().trim()
                    merged[key] = ServerCrmClient(
                        identity = "profile-crm:$key",
                        phone = marker.phone.ifBlank { key },
                        normalizedPhone = PhoneNormalizer.normalize(marker.phone).ifBlank { key },
                        name = localName,
                        lastActivityAtMs = marker.updatedAtMs.coerceAtLeast(0L),
                        isCrm = true,
                        crmUpdatedAtMs = marker.updatedAtMs.coerceAtLeast(0L),
                        phase = null,
                        phaseUpdatedAtMs = 0L,
                        companyIds = emptySet(),
                        userStates = emptyList(),
                        notes = emptyList(),
                        searchSnippet = "",
                    )
                } else {
                    merged[key] = existing.copy(
                        isCrm = true,
                        crmUpdatedAtMs = maxOf(existing.crmUpdatedAtMs, marker.updatedAtMs),
                    )
                }
            }
        }

        var items = merged.values.toList()
        if (filterState.crmOnly) {
            items = items.filter { client ->
                client.isCrm == true || CrmContactSyncStore.isEnabled(context, client.phone)
            }
        }
        items = items.sortedWith(
            compareByDescending<ServerCrmClient> { it.lastActivityAtMs }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.normalizedPhone },
        )

        if (items.isEmpty()) return null
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1)
        return ServerCrmContactsPage(
            clients = items.drop(safeOffset).take(safeLimit),
            total = items.size,
            limit = safeLimit,
            offset = safeOffset,
        )
    }

    private fun collectScope(
        context: Context,
        config: AppConfig,
        filterState: HomeCrmFilterState,
        searchQuery: String,
        destination: MutableMap<String, ServerCrmClient>,
    ) {
        var requestOffset = 0
        while (requestOffset < FALLBACK_MAX_ITEMS) {
            val page = runCatching {
                ServerCrmContactsClient.lookupPage(
                    config = config,
                    filterState = filterState,
                    searchQuery = searchQuery,
                    limit = FALLBACK_CHUNK_SIZE,
                    offset = requestOffset,
                    context = context,
                )
            }.getOrNull() ?: return

            page.clients.forEach { incoming ->
                val key = clientKey(incoming.phone)
                if (key.isBlank()) return@forEach
                destination[key] = destination[key]?.let { mergeClient(it, incoming) } ?: incoming
            }
            if (page.clients.isEmpty()) return
            requestOffset += page.clients.size
            if (requestOffset >= page.total || page.clients.size < page.limit) return
        }
    }

    private fun mergeClient(existing: ServerCrmClient, incoming: ServerCrmClient): ServerCrmClient {
        val newer = if (incoming.lastActivityAtMs >= existing.lastActivityAtMs) incoming else existing
        val older = if (newer === incoming) existing else incoming
        val notes = (existing.notes + incoming.notes)
            .groupBy { it.id }
            .mapNotNull { (_, versions) -> versions.maxByOrNull { it.updatedAtMs } }
            .sortedByDescending { it.updatedAtMs }
        val users = (existing.userStates + incoming.userStates)
            .groupBy { it.userId }
            .mapNotNull { (_, versions) ->
                versions.maxByOrNull { maxOf(it.crmUpdatedAtMs, it.phaseUpdatedAtMs) }
            }
        val crmActive = when {
            existing.isCrm == true || incoming.isCrm == true -> true
            existing.isCrm == false && incoming.isCrm == false -> false
            else -> newer.isCrm ?: older.isCrm
        }
        val phaseSource = if (incoming.phaseUpdatedAtMs >= existing.phaseUpdatedAtMs) incoming else existing
        return newer.copy(
            identity = newer.identity.ifBlank { older.identity },
            phone = newer.phone.ifBlank { older.phone },
            normalizedPhone = newer.normalizedPhone.ifBlank { older.normalizedPhone },
            name = newer.name.ifBlank { older.name },
            isCrm = crmActive,
            crmUpdatedAtMs = maxOf(existing.crmUpdatedAtMs, incoming.crmUpdatedAtMs),
            phase = phaseSource.phase,
            phaseUpdatedAtMs = maxOf(existing.phaseUpdatedAtMs, incoming.phaseUpdatedAtMs),
            companyIds = existing.companyIds + incoming.companyIds,
            userStates = users,
            notes = notes,
            searchSnippet = newer.searchSnippet.ifBlank { older.searchSnippet },
        )
    }

    private fun clientKey(phone: String): String = PhoneNormalizer.key(phone)

    private fun matchesSearch(context: Context, phone: String, searchQuery: String): Boolean {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) return true
        val name = ContactGroupFilter.resolveDisplayName(context, phone).orEmpty().lowercase()
        return phone.lowercase().contains(query) ||
            PhoneNormalizer.display(phone).lowercase().contains(query) ||
            name.contains(query)
    }
}
