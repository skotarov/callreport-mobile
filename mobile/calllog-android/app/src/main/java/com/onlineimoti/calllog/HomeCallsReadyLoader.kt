package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Loads local and CRM call pages while protecting Home from stale async results. */
internal class HomeCallsLoader(
    private val activity: HomeActivity,
    private val handler: Handler,
    private val contentRenderer: HomeContentRenderer,
    private val crmFilters: HomeCrmFiltersController,
    private val serverCallNotes: HomeServerCallNotesController,
    private val activePhoneFilter: () -> String,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    private val isCrmModeEnabled: () -> Boolean,
    private val onRenderComplete: () -> Unit,
    private val onCrmCallsRendered: (Int) -> Unit = {},
    private val onCrmCallsEmpty: () -> Unit = {},
) {
    private val localExecutor = Executors.newSingleThreadExecutor()
    private val localNotesExecutor = Executors.newSingleThreadExecutor()
    private val crmExecutor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    fun invalidate(): Int = generation.incrementAndGet()

    fun release() {
        generation.incrementAndGet()
        localExecutor.shutdownNow()
        localNotesExecutor.shutdownNow()
        crmExecutor.shutdownNow()
    }

    fun renderLocalCalls(pageSize: Int) {
        HomePageReadyState.markLoading()
        val requestedPage = pageIndex()
        val phoneFilter = activePhoneFilter()
        val searchQuery = activeSearchQuery()
        val expectedGeneration = generation.get()
        val appContext = activity.applicationContext
        val visibleRevision = AtomicInteger(0)
        val firstRenderCompleted = AtomicBoolean(false)
        val cacheEligible = phoneFilter.isBlank() && searchQuery.isBlank()
        val cachedCalls = if (cacheEligible) {
            HomeCallLogSnapshotCache.readPage(appContext, requestedPage, pageSize)
        } else {
            emptyList()
        }
        val cachedData = cachedCalls.takeIf { it.isNotEmpty() }?.let { calls ->
            HomeNotesSnapshotCache.mergeCached(
                appContext,
                HomeRenderData(calls, emptyMap(), emptyMap(), emptyMap()),
            )
        }

        fun completeFirstRender() {
            if (firstRenderCompleted.compareAndSet(false, true)) onRenderComplete()
        }

        if (cachedData != null) {
            // The first frame is a complete last-known Call Log with its yellow and blue
            // notes. Android providers and the server are checked only after it is visible.
            contentRenderer.applyProvisionalRenderData(cachedData, pageSize)
            completeFirstRender()
            startLocalEnrichment(
                calls = cachedCalls,
                pageSize = pageSize,
                expectedGeneration = expectedGeneration,
                requestedPage = requestedPage,
                phoneFilter = phoneFilter,
                searchQuery = searchQuery,
                visibleRevision = visibleRevision,
                expectedVisibleRevision = visibleRevision.get(),
            )
        } else {
            contentRenderer.showLoading()
        }

        localExecutor.execute {
            val calls = loadLocalCalls(appContext, phoneFilter, searchQuery, requestedPage, pageSize)
            if (cacheEligible && calls.isNotEmpty()) {
                HomeCallLogSnapshotCache.storePage(appContext, requestedPage, pageSize, calls)
            }
            handler.post {
                if (!isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) return@post
                if (calls.isEmpty()) {
                    if (cachedCalls.isEmpty()) {
                        contentRenderer.renderEmptyState()
                        HomePageReadyState.markReady()
                        completeFirstRender()
                    }
                    return@post
                }

                if (calls == cachedCalls) {
                    // The cache is current. Parallel local/server enrichment already runs
                    // against these exact rows, so touching the list again would be noise.
                    return@post
                }

                val nextVisibleRevision = visibleRevision.incrementAndGet()
                serverCallNotes.cancelPending()
                val freshData = HomeNotesSnapshotCache.mergeCached(
                    appContext,
                    HomeRenderData(calls, emptyMap(), emptyMap(), emptyMap()),
                )
                contentRenderer.applyProvisionalRenderData(freshData, pageSize)
                completeFirstRender()
                startLocalEnrichment(
                    calls = calls,
                    pageSize = pageSize,
                    expectedGeneration = expectedGeneration,
                    requestedPage = requestedPage,
                    phoneFilter = phoneFilter,
                    searchQuery = searchQuery,
                    visibleRevision = visibleRevision,
                    expectedVisibleRevision = nextVisibleRevision,
                )
            }
        }
    }

    /** Local note reads and the remote refresh run beside the Android Call Log check. */
    private fun startLocalEnrichment(
        calls: List<PhoneCallRecord>,
        pageSize: Int,
        expectedGeneration: Int,
        requestedPage: Int,
        phoneFilter: String,
        searchQuery: String,
        visibleRevision: AtomicInteger,
        expectedVisibleRevision: Int,
    ) {
        if (calls.isEmpty()) return
        val appContext = activity.applicationContext
        val localNotesApplied = AtomicBoolean(false)
        val fastData = HomeRenderData(calls, emptyMap(), emptyMap(), emptyMap())
        serverCallNotes.enrichAsync(
            fastData,
            onFinished = {
                if (isCurrentLocalStage(
                        expectedGeneration,
                        requestedPage,
                        phoneFilter,
                        searchQuery,
                        visibleRevision,
                        expectedVisibleRevision,
                    )
                ) {
                    HomePageReadyState.markReady()
                }
            },
        ) { enriched ->
            if (!isCurrentLocalStage(
                    expectedGeneration,
                    requestedPage,
                    phoneFilter,
                    searchQuery,
                    visibleRevision,
                    expectedVisibleRevision,
                )
            ) return@enrichAsync
            if (!localNotesApplied.get()) contentRenderer.applySupplementalRenderData(enriched, pageSize)
        }

        runCatching {
            localNotesExecutor.execute notesTask@{
                if (!isCurrentLocalStage(
                        expectedGeneration,
                        requestedPage,
                        phoneFilter,
                        searchQuery,
                        visibleRevision,
                        expectedVisibleRevision,
                    )
                ) return@notesTask
                val data = runCatching {
                    HomeRenderData(
                        calls = calls,
                        contactNotesByNumber = HomeCallPageLoader.contactNotes(appContext, calls),
                        contactNamesByNumber = HomeCallPageLoader.contactNames(appContext, calls),
                        callNotesByCall = HomeCallNotesResolver.localNotes(appContext, calls),
                    )
                }.getOrElse {
                    handler.post {
                        if (isCurrentLocalStage(
                                expectedGeneration,
                                requestedPage,
                                phoneFilter,
                                searchQuery,
                                visibleRevision,
                                expectedVisibleRevision,
                            )
                        ) {
                            HomePageReadyState.markReady()
                        }
                    }
                    return@notesTask
                }
                handler.post {
                    if (!isCurrentLocalStage(
                            expectedGeneration,
                            requestedPage,
                            phoneFilter,
                            searchQuery,
                            visibleRevision,
                            expectedVisibleRevision,
                        )
                    ) return@post
                    localNotesApplied.set(true)
                    contentRenderer.applySupplementalRenderData(data, pageSize)
                    serverCallNotes.enrichAsync(
                        data,
                        onFinished = {
                            if (isCurrentLocalStage(
                                    expectedGeneration,
                                    requestedPage,
                                    phoneFilter,
                                    searchQuery,
                                    visibleRevision,
                                    expectedVisibleRevision,
                                )
                            ) {
                                HomePageReadyState.markReady()
                            }
                        },
                    ) { enriched ->
                        if (!isCurrentLocalStage(
                                expectedGeneration,
                                requestedPage,
                                phoneFilter,
                                searchQuery,
                                visibleRevision,
                                expectedVisibleRevision,
                            )
                        ) return@enrichAsync
                        contentRenderer.applyRenderData(enriched, pageSize)
                    }
                }
            }
        }.onFailure {
            handler.post {
                if (isCurrentLocalStage(
                        expectedGeneration,
                        requestedPage,
                        phoneFilter,
                        searchQuery,
                        visibleRevision,
                        expectedVisibleRevision,
                    )
                ) {
                    HomePageReadyState.markReady()
                }
            }
        }
    }

    private fun loadLocalCalls(
        context: Context,
        phoneFilter: String,
        searchQuery: String,
        requestedPage: Int,
        pageSize: Int,
    ): List<PhoneCallRecord> = when {
        phoneFilter.isBlank() && searchQuery.isBlank() -> HomeTimelineLoader.page(
            context = context,
            pageIndex = requestedPage,
            pageSize = pageSize,
        )
        searchQuery.isNotBlank() -> searchResultsWithSms(context, phoneFilter, searchQuery, requestedPage, pageSize)
        else -> HomeCallPageLoader.calls(
            context = context,
            activePhoneFilter = phoneFilter,
            searchQuery = searchQuery,
            pageIndex = requestedPage,
            pageSize = pageSize,
            crmMode = false,
        )
    }

    private fun searchResultsWithSms(
        context: Context,
        phoneFilter: String,
        query: String,
        requestedPage: Int,
        pageSize: Int,
    ): List<PhoneCallRecord> {
        if (HomeCallPageLoader.isSearchTooShort(query)) return emptyList()
        val baseResults = HomeCallPageLoader.calls(
            context = context,
            activePhoneFilter = phoneFilter,
            searchQuery = query,
            pageIndex = 0,
            pageSize = SEARCH_RESULT_SCAN_LIMIT,
            crmMode = false,
        )
        val selectedPhoneKey = HomeCallPageLoader.noteKey(phoneFilter)
        val smsResults = SmsMessageReader.searchMessages(context, query, SEARCH_RESULT_SCAN_LIMIT)
            .asSequence()
            .filter { selectedPhoneKey.isBlank() || HomeCallPageLoader.noteKey(it.address) == selectedPhoneKey }
            .mapNotNull { message ->
                message.address.takeIf { it.isNotBlank() }?.let { address ->
                    PhoneCallRecord(
                        number = address,
                        name = "",
                        direction = if (message.isOutgoing) "sms_out" else "sms_in",
                        startedAt = message.timestampMs,
                        durationSeconds = 0L,
                        smsBody = message.body,
                        providerId = message.providerId,
                    )
                }
            }
            .toList()
        val seen = linkedSetOf<String>()
        val combined = (baseResults + smsResults)
            .filter { seen.add(searchResultKey(it)) }
            .sortedByDescending { it.startedAt }
        return TimelinePageMode.phoneDayPage(context, combined, requestedPage, pageSize)
    }

    private fun searchResultKey(row: PhoneCallRecord): String {
        if (row.isSms) {
            val fallback = "${row.number}:${row.startedAt}:${row.smsBody.hashCode()}"
            return "sms:${row.providerId.ifBlank { fallback }}"
        }
        val fallback = "${row.number}:${row.startedAt}:${row.direction}"
        return "call:${row.providerId.ifBlank { fallback }}"
    }

    fun renderCrmCallsAsync(pageSize: Int, expectedGeneration: Int) {
        HomePageReadyState.markLoading()
        val filterState = crmFilters.state()
        if (contentRenderer.currentCalls.isEmpty()) contentRenderer.showCrmLoading()
        val requestedPage = pageIndex()
        val appContext = activity.applicationContext
        val localNotesApplied = AtomicBoolean(false)
        crmExecutor.execute {
            val calls = runCatching {
                val localFiltered = HomeCrmFilterEngine.filterLocal(
                    context = appContext,
                    calls = HomeTimelineLoader.crmCandidates(appContext),
                    state = filterState,
                )
                val companyFiltered = if (filterState.isCompanyFiltered) {
                    val memberships = HomeCrmCompanyMembershipStore.resolve(
                        context = appContext,
                        config = ConfigStore.load(appContext),
                        phones = localFiltered.map { it.number },
                    )
                    HomeCrmFilterEngine.filterByCompany(localFiltered, filterState, memberships.companyIdsByPhoneKey)
                } else localFiltered
                TimelinePageMode.phoneDayPage(appContext, companyFiltered, requestedPage, pageSize)
            }.getOrDefault(emptyList())
            val fastData = HomeRenderData(calls, emptyMap(), emptyMap(), emptyMap())
            handler.post {
                if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@post
                if (calls.isEmpty()) {
                    contentRenderer.renderEmptyState()
                    HomePageReadyState.markReady()
                    onCrmCallsEmpty()
                } else {
                    contentRenderer.applyProvisionalRenderData(fastData, pageSize)
                    serverCallNotes.enrichAsync(fastData) { enriched ->
                        if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@enrichAsync
                        if (!localNotesApplied.get()) contentRenderer.applySupplementalRenderData(enriched, pageSize)
                    }
                    onCrmCallsRendered(calls.size)
                }
                onRenderComplete()
            }
            if (calls.isEmpty()) return@execute

            runCatching {
                localNotesExecutor.execute crmNotesTask@{
                    if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@crmNotesTask
                    val data = runCatching {
                        HomeRenderData(
                            calls = calls,
                            contactNotesByNumber = HomeCallPageLoader.contactNotes(appContext, calls),
                            contactNamesByNumber = HomeCallPageLoader.contactNames(appContext, calls),
                            callNotesByCall = HomeCallNotesResolver.localNotes(appContext, calls),
                        )
                    }.getOrElse {
                        handler.post {
                            if (isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) {
                                HomePageReadyState.markReady()
                            }
                        }
                        return@crmNotesTask
                    }
                    handler.post {
                        if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@post
                        localNotesApplied.set(true)
                        contentRenderer.applySupplementalRenderData(data, pageSize)
                        serverCallNotes.enrichAsync(
                            data,
                            onFinished = {
                                if (isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) {
                                    HomePageReadyState.markReady()
                                }
                            },
                        ) { enriched ->
                            if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@enrichAsync
                            contentRenderer.applyRenderData(enriched, pageSize)
                        }
                    }
                }
            }.onFailure {
                handler.post {
                    if (isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) {
                        HomePageReadyState.markReady()
                    }
                }
            }
        }
    }

    private fun isCurrentLocalStage(
        expectedGeneration: Int,
        requestedPage: Int,
        phoneFilter: String,
        searchQuery: String,
        visibleRevision: AtomicInteger,
        expectedVisibleRevision: Int,
    ): Boolean = visibleRevision.get() == expectedVisibleRevision &&
        isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)

    private fun isCurrentLocalRender(
        expectedGeneration: Int,
        requestedPage: Int,
        phoneFilter: String,
        searchQuery: String,
    ): Boolean = expectedGeneration == generation.get() &&
        !activity.isFinishing && !activity.isDestroyed &&
        activePhoneFilter() == phoneFilter && activeSearchQuery() == searchQuery &&
        pageIndex() == requestedPage

    private fun isCurrentCrmRender(
        expectedGeneration: Int,
        requestedPage: Int,
        filterState: HomeCrmFilterState,
    ): Boolean = expectedGeneration == generation.get() &&
        !activity.isFinishing && !activity.isDestroyed && isCrmModeEnabled() &&
        activePhoneFilter().isBlank() && activeSearchQuery().isBlank() &&
        pageIndex() == requestedPage && crmFilters.state() == filterState

    private companion object {
        const val SEARCH_RESULT_SCAN_LIMIT = 500
    }
}
