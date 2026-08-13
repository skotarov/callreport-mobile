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
        CrmContactProfileScopeMigration.migrateKnownAliases(appContext)
        CrmContactSyncStore.refreshFromServer(appContext)
        val config = ConfigStore.load(appContext)

        // The hand/person marker shown on a Clients row comes from the synchronized
        // profile CRM store (with server is_crm only as an additional hint). Do not ask
        // older/mixed server deployments to apply crm_only=1 because that can use a
        // different legacy profile scope and return an empty list even for rows that
        // Android correctly marks as CRM. Fetch the broad scope first, then apply the
        // exact same CRM predicate locally before pagination.
        if (filterState.crmOnly) {
            return loadPersonalCrmPage(
                context = appContext,
                config = config,
                filterState = filterState,
                searchQuery = searchQuery,
                limit = limit,
                offset = offset,
            )
        }

        val primary = ServerCrmContactsClient.lookupPage(
            config = config,
            filterState = filterState,
            searchQuery = searchQuery,
            limit = limit,
            offset = offset,
            context = appContext,
        )

        // Company-scoped requests are already known to work on the mixed production
        // data. Preserve their normal server pagination and semantics.
        if (filterState.hasCompanyFilter || primary.clients.isNotEmpty() || primary.total > 0) {
            return primary
        }

        return loadCompatibilityPage(
            context = appContext,
            config = config,
            filterState = filterState,
            searchQuery = searchQuery,
            limit = limit,
            offset = offset,
        ) ?: primary
    }

    /**
     * CRM filtering is profile-owned Android state. Fetch all rows matching the other
     * filters, keep only rows carrying the same hand/person predicate as the renderer,
     * and paginate only after that filtering.
     */
    private fun loadPersonalCrmPage(
        context: Context,
        config: AppConfig,
        filterState: HomeCrmFilterState,
        searchQuery: String,
        limit: Int,
        offset: Int,
    ): ServerCrmContactsPage {
        val broadState = filterState.copy(crmOnly = false)
        val merged = linkedMapOf<String, ServerCrmClient>()

        if (filterState.hasCompanyFilter) {
            // This is the important path for e.g. Maxim + hand/person. The firm query
            // already works in production, so collect that full result without asking
            // the server to interpret the personal CRM flag.
            collectScope(
                context = context,
                config = config,
                filterState = broadState,
                searchQuery = searchQuery,
                destination = merged,
            )
        } else {
            // With no company selected, reconstruct the broad visible universe from the
            // same company scopes that are known to work on mixed/legacy deployments.
            val companies = runCatching {
                CallReportTopicCompaniesRepository.load(context, config).companies
            }.getOrDefault(emptyList())
            val companyIds = companies.map { it.id.trim() }.filter { it.isNotBlank() }.distinct().toSet()
            if (companyIds.isNotEmpty()) {
                collectScope(
                    context = context,
                    config = config,
                    filterState = broadState.copy(companyIds = companyIds),
                    searchQuery = searchQuery,
                    destination = merged,
                )
            }
            collectScope(
                context = context,
                config = config,
                filterState = broadState.copy(companyIds = setOf("none")),
                searchQuery = searchQuery,
                destination = merged,
            )
        }

        // If an active personal CRM marker is absent from the old Clients endpoint,
        // materialize it only when no company/phase restriction would make membership
        // ambiguous. This keeps "my clients" complete without leaking it into a firm.
        if (!filterState.hasCompanyFilter && !filterState.hasPhaseFilter) {
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

        val crmClients = merged.values
            .filter { client ->
                client.isCrm == true || CrmContactSyncStore.isEnabled(context, client.phone)
            }
            .sortedWith(clientOrdering())

        return paginate(crmClients, limit, offset)
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

        collectScope(
            context = context,
            config = config,
            filterState = broadState.copy(companyIds = setOf("none")),
            searchQuery = searchQuery,
            destination = merged,
        )

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

        val items = merged.values.sortedWith(clientOrdering())
        if (items.isEmpty()) return null
        return paginate(items, limit, offset)
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

    private fun paginate(
        items: List<ServerCrmClient>,
        limit: Int,
        offset: Int,
    ): ServerCrmContactsPage {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1)
        return ServerCrmContactsPage(
            clients = items.drop(safeOffset).take(safeLimit),
            total = items.size,
            limit = safeLimit,
            offset = safeOffset,
        )
    }

    private fun clientOrdering(): Comparator<ServerCrmClient> =
        compareByDescending<ServerCrmClient> { it.lastActivityAtMs }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.normalizedPhone }

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
