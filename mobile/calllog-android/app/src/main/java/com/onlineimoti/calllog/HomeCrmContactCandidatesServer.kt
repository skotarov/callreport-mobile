package com.onlineimoti.calllog

import android.content.Context
import android.util.Log

internal object HomeCrmContactCandidatesServer {
    private const val TAG = "ClientsLoader"
    private const val FALLBACK_CHUNK_SIZE = 100
    private const val FALLBACK_MAX_ITEMS = 10_000

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
        accessibleCompanyIds: List<String> = emptyList(),
    ): ServerCrmContactsPage {
        val appContext = context.applicationContext
        CrmContactProfileScopeMigration.migrateKnownAliases(appContext)
        CrmContactSyncStore.refreshFromServer(appContext)
        val config = ConfigStore.load(appContext)
        val companyIds = resolveAccessibleCompanyIds(appContext, config, accessibleCompanyIds)

        if (!filterState.isActive) {
            // The server is authoritative for the unfiltered Clients screen. An empty
            // company selection means "all clients visible to this profile", not none.
            // Prefer that canonical request so server-side pagination/search remain intact.
            val primary = ServerCrmContactsClient.lookupPage(
                config = config,
                filterState = filterState,
                searchQuery = searchQuery,
                limit = limit,
                offset = offset,
                context = appContext,
            )
            logPageSource("primary-neutral", primary, filterState)
            if (ClientsPrimaryPagePolicy.shouldAccept(primary.clients.size, hasCompanyFilter = false)) {
                return primary
            }

            // Some mixed/legacy deployments report a broad non-zero total while returning
            // no rows for the neutral company scope. An empty page must therefore fall
            // through to explicit accessible-company scopes instead of being accepted
            // solely because total > 0.
            val fallback = loadAllAccessibleCompaniesPage(
                context = appContext,
                config = config,
                filterState = filterState,
                searchQuery = searchQuery,
                limit = limit,
                offset = offset,
                companyIds = companyIds,
            )
            logPageSource("fallback-all-companies", fallback, filterState)
            return fallback
        }

        if (filterState.crmOnly) {
            if (!filterState.hasCompanyFilter) {
                // With "My clients" enabled and no company selected, ask the server
                // directly for the current user's CRM clients across the neutral
                // all-company scope. Do not depend on the local company list/cache.
                val primary = ServerCrmContactsClient.lookupPage(
                    config = config,
                    filterState = filterState,
                    searchQuery = searchQuery,
                    limit = limit,
                    offset = offset,
                    context = appContext,
                )
                logPageSource("primary-my-clients", primary, filterState)
                if (ClientsPrimaryPagePolicy.shouldAccept(primary.clients.size, hasCompanyFilter = false)) {
                    return primary
                }
            }

            // Compatibility fallback for older deployments that need explicit company
            // scopes before the current user's CRM markers can be reconstructed.
            val fallback = loadPersonalCrmPage(
                context = appContext,
                config = config,
                filterState = filterState,
                searchQuery = searchQuery,
                limit = limit,
                offset = offset,
                companyIds = companyIds,
            )
            logPageSource("fallback-my-clients", fallback, filterState)
            return fallback
        }

        val primary = ServerCrmContactsClient.lookupPage(
            config = config,
            filterState = filterState,
            searchQuery = searchQuery,
            limit = limit,
            offset = offset,
            context = appContext,
        )
        logPageSource("primary-filtered", primary, filterState)

        if (ClientsPrimaryPagePolicy.shouldAccept(primary.clients.size, filterState.hasCompanyFilter)) {
            return primary
        }

        val fallback = loadCompatibilityPage(
            context = appContext,
            config = config,
            filterState = filterState,
            searchQuery = searchQuery,
            limit = limit,
            offset = offset,
            companyIds = companyIds,
        )
        if (fallback != null) {
            logPageSource("fallback-filtered", fallback, filterState)
            return fallback
        }
        return primary
    }

    private fun loadAllAccessibleCompaniesPage(
        context: Context,
        config: AppConfig,
        filterState: HomeCrmFilterState,
        searchQuery: String,
        limit: Int,
        offset: Int,
        companyIds: List<String>,
    ): ServerCrmContactsPage {
        val merged = linkedMapOf<String, ServerCrmClient>()
        val broadState = filterState.copy(crmOnly = false, companyIds = emptySet())
        companyIds.forEach { companyId ->
            collectScope(
                context = context,
                config = config,
                filterState = broadState.copy(companyIds = setOf(companyId)),
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
        return paginate(merged.values.sortedWith(clientOrdering()), limit, offset)
    }

    private fun loadPersonalCrmPage(
        context: Context,
        config: AppConfig,
        filterState: HomeCrmFilterState,
        searchQuery: String,
        limit: Int,
        offset: Int,
        companyIds: List<String>,
    ): ServerCrmContactsPage {
        val broadState = filterState.copy(crmOnly = false)
        val merged = linkedMapOf<String, ServerCrmClient>()

        if (filterState.hasCompanyFilter) {
            collectScope(
                context = context,
                config = config,
                filterState = broadState,
                searchQuery = searchQuery,
                destination = merged,
            )
        } else {
            companyIds.forEach { companyId ->
                collectScope(
                    context = context,
                    config = config,
                    filterState = broadState.copy(companyIds = setOf(companyId)),
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
        companyIds: List<String>,
    ): ServerCrmContactsPage? {
        val broadState = filterState.copy(crmOnly = false)
        val merged = linkedMapOf<String, ServerCrmClient>()

        companyIds.forEach { companyId ->
            collectScope(
                context = context,
                config = config,
                filterState = broadState.copy(companyIds = setOf(companyId)),
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

    private fun resolveAccessibleCompanyIds(
        context: Context,
        config: AppConfig,
        preferredIds: List<String>,
    ): List<String> {
        val preferred = preferredIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (preferred.isNotEmpty()) return preferred

        val cached = CallReportTopicCompaniesCache.read(context, config)?.companies.orEmpty()
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (cached.isNotEmpty()) return cached

        return runCatching {
            CallReportTopicCompaniesRepository.refresh(context, config).companies
        }.getOrDefault(emptyList())
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .distinct()
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

    private fun logPageSource(
        source: String,
        page: ServerCrmContactsPage,
        filterState: HomeCrmFilterState,
    ) {
        Log.d(
            TAG,
            "source=$source rows=${page.clients.size} total=${page.total} offset=${page.offset} " +
                "limit=${page.limit} crmOnly=${filterState.crmOnly} " +
                "companyFilter=${filterState.hasCompanyFilter} phaseFilter=${filterState.hasPhaseFilter}",
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
