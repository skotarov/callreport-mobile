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
        val legacySnapshot = if (cachedPage == null && searchQuery.isBlank()) runCatching {
            HomeCrmContactsSnapshotCache.read(appContext, config, filterState, requestedPage, pageSize)
        }.getOrNull() else null
        val hasCache = cachedPage != null || legacySnapshot != null

        val busyToken = HomeBusyTooltipUi.begin(activity, HomeBusyWork.CLIENTS)
        busyTokens += busyToken
        when {
            cachedPage != null -> contactsContent.render(
                data = cachedPage.data,
                pageSize = pageSize,
                refreshCompanyLabels = false,
                totalItems = cachedPage.total,
                serverOffset = cachedPage.offset,
                stale = true,
            )
            legacySnapshot != null -> contactsContent.render(
                data = legacySnapshot,
                pageSize = pageSize,
                refreshCompanyLabels = false,
                stale = true,
            )
            else -> contactsContent.showLoading()
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
                val calls = serverPage.calls.map(::enrichWithLocalName)
                val contactNotes = HomeCallPageLoader.contactNotes(appContext, calls).toMutableMap()
                val callNotes = linkedMapOf<String, String>()
                runCatching { HomeCrmClientServerNotes.snapshot(appContext, calls) }.getOrNull()?.let { notes ->
                    contactNotes.putAll(notes.contactNotesByNumber)
                    callNotes.putAll(notes.callNotesByCall)
                }
                serverPage.clients.forEach { client ->
                    val latest = client.notes.maxByOrNull { note -> maxOf(note.updatedAtMs, note.createdAtMs) } ?: return@forEach
                    val key = HomeCallPageLoader.noteKey(client.phone)
                    if (key.isNotBlank()) {
                        contactNotes[key] = if (latest.authorName.isBlank()) latest.text else "${latest.authorName}: ${latest.text}"
                    }
                }
                val data = HomeRenderData(
                    calls = calls,
                    contactNotesByNumber = contactNotes,
                    contactNamesByNumber = calls.associate { call -> HomeCallPageLoader.noteKey(call.number) to call.displayName },
                    callNotesByCall = callNotes,
                )
                runCatching { repository.storePage(appContext, config, filterState, searchQuery, serverPage) }
                if (searchQuery.isBlank()) runCatching {
                    HomeCrmContactsSnapshotCache.write(appContext, config, filterState, requestedPage, pageSize, data)
                }
                ServerRenderPage(data, serverPage.total, serverPage.limit, serverPage.offset)
            }

            handler.post {
                finishBusy(busyToken)
                if (!isCurrent(expectedGeneration, requestedPage, filterState, searchQuery)) return@post
                renderResult.onSuccess { result ->
                    if (result.total == 0) {
                        contactsContent.renderEmpty(pageSize)
                    } else {
                        contactsContent.render(
                            data = result.data,
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
        activePhoneFilter().isBlank() && activeSearchQuery().trim() == searchQuery &&
        pageIndex() == requestedPage && crmFilters.state() == filterState

    private fun finishBusy(token: Long) {
        busyTokens.remove(token)
        HomeBusyTooltipUi.end(activity, token)
    }

    private fun finishAllBusy() {
        busyTokens.toList().forEach(::finishBusy)
    }

    private fun enrichWithLocalName(contact: PhoneCallRecord): PhoneCallRecord {
        val localName = ContactGroupFilter.resolveDisplayName(activity.applicationContext, contact.number).orEmpty().trim()
        return if (localName.isBlank()) contact else contact.copy(name = localName)
    }

    private data class ServerRenderPage(
        val data: HomeRenderData,
        val total: Int,
        val limit: Int,
        val offset: Int,
    )
}
