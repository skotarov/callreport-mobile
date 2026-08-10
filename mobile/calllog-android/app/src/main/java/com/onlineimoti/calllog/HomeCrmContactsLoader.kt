package com.onlineimoti.calllog

import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Cache-first, server-authoritative loader for the Clients screen. */
internal class HomeCrmContactsLoader(
    private val activity: HomeActivity,
    private val handler: Handler,
    private val contactsContent: HomeCrmContactsContentView,
    private val crmFilters: HomeCrmFiltersController,
    private val activePhoneFilter: () -> String,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    @Suppress("UNUSED_PARAMETER") private val isCrmModeEnabled: () -> Boolean,
    private val isCrmContactsMode: () -> Boolean,
    private val onRenderComplete: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    private val busyTokens = linkedSetOf<Long>()
    private var lastCrmOnlyState = false

    fun invalidate(): Int {
        finishAllBusy()
        return generation.incrementAndGet()
    }

    fun release() {
        generation.incrementAndGet()
        finishAllBusy()
        executor.shutdownNow()
    }

    fun renderAsync(pageSize: Int, expectedGeneration: Int) {
        val filterState = crmFilters.state()
        val forceCrmMarkerRefresh = filterState.crmOnly && !lastCrmOnlyState
        lastCrmOnlyState = filterState.crmOnly
        val requestedPage = pageIndex().coerceAtLeast(0)
        val requestedOffset = requestedPage * pageSize
        val searchQuery = activeSearchQuery().trim()
        val appContext = activity.applicationContext
        val config = ConfigStore.load(appContext)
        val repository = ClientsCacheRepository.get(appContext)
        val cachedPage = runCatching {
            repository.loadPage(config, filterState, searchQuery, pageSize, requestedOffset)
        }.getOrNull()
        val hasCache = cachedPage != null

        val busyToken = HomeBusyTooltipUi.begin(activity, HomeBusyWork.CLIENTS)
        busyTokens += busyToken
        if (cachedPage != null) {
            contactsContent.render(
                clients = cachedPage.clients,
                pageSize = pageSize,
                refreshCompanyLabels = false,
                totalItems = cachedPage.total,
                serverOffset = cachedPage.offset,
                stale = true,
            )
        } else {
            contactsContent.showLoading()
        }

        executor.execute {
            if (forceCrmMarkerRefresh) runCatching {
                CrmContactSyncStore.refreshFromServer(appContext, force = true)
            }

            val pageResult = runCatching {
                HomeCrmContactCandidatesServer.loadPage(
                    context = appContext,
                    filterState = filterState,
                    searchQuery = searchQuery,
                    limit = pageSize,
                    offset = requestedOffset,
                )
            }
            val renderResult = pageResult.mapCatching { serverPage ->
                val clients = serverPage.clients.map(::enrichWithLocalName)
                runCatching { repository.storePage(appContext, config, filterState, searchQuery, serverPage) }
                ServerRenderPage(clients, serverPage.total, serverPage.limit, serverPage.offset)
            }

            handler.post {
                finishBusy(busyToken)
                if (!isCurrent(expectedGeneration, requestedPage, filterState, searchQuery)) return@post
                renderResult.onSuccess { result ->
                    if (result.total == 0) {
                        contactsContent.renderEmpty(pageSize)
                    } else {
                        contactsContent.render(
                            clients = result.clients,
                            pageSize = pageSize,
                            totalItems = result.total,
                            serverOffset = result.offset,
                            stale = false,
                        )
                    }
                }.onFailure {
                    contactsContent.renderRefreshError(
                        pageSize = pageSize,
                        hasCachedRows = hasCache,
                        onRetry = { renderAsync(pageSize, expectedGeneration) },
                    )
                }
                onRenderComplete()
            }
        }
    }

    private fun isCurrent(
        expectedGeneration: Int,
        requestedPage: Int,
        filterState: HomeCrmFilterState,
        searchQuery: String,
    ): Boolean = expectedGeneration == generation.get() &&
        !activity.isFinishing && !activity.isDestroyed && isCrmContactsMode() &&
        activeSearchQuery().trim() == searchQuery &&
        pageIndex() == requestedPage && crmFilters.state() == filterState

    private fun finishBusy(token: Long) {
        busyTokens.remove(token)
        HomeBusyTooltipUi.end(activity, token)
    }

    private fun finishAllBusy() {
        busyTokens.toList().forEach(::finishBusy)
    }

    private fun enrichWithLocalName(client: ServerCrmClient): ServerCrmClient {
        val localName = ContactGroupFilter.resolveDisplayName(activity.applicationContext, client.phone).orEmpty().trim()
        return if (localName.isBlank()) client else client.copy(name = localName)
    }

    private data class ServerRenderPage(
        val clients: List<ServerCrmClient>,
        val total: Int,
        val limit: Int,
        val offset: Int,
    )
}