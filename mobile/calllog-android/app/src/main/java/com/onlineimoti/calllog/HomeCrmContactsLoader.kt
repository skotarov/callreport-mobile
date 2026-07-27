package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads the authenticated user's Clients page. CRM-only rows are rendered from
 * the local profile cache first, then reconciled with the server in background.
 */
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
        val requestedPage = pageIndex()
        val appContext = activity.applicationContext
        val busyToken = HomeBusyTooltipUi.begin(activity, HomeBusyWork.CLIENTS)
        busyTokens += busyToken
        contactsContent.showLoading()

        executor.execute {
            var provisionalAvailable = false
            if (filterState.crmOnly) {
                val provisionalCalls = runCatching {
                    pageContacts(
                        filteredLocalCrmContacts(appContext, filterState),
                        requestedPage,
                        pageSize,
                    )
                }.getOrDefault(emptyList())
                if (provisionalCalls.isNotEmpty()) {
                    provisionalAvailable = true
                    val provisionalData = provisionalData(provisionalCalls)
                    handler.post {
                        if (!isCurrent(expectedGeneration, requestedPage, filterState)) return@post
                        contactsContent.render(
                            data = provisionalData,
                            pageSize = pageSize,
                            refreshCompanyLabels = false,
                        )
                    }
                }
            }

            val finalResult = runCatching {
                val serverContacts = HomeCrmContactCandidates.load(appContext, filterState)
                val contacts = if (filterState.crmOnly) {
                    // The server result already respects phase/company filters. Local
                    // winners must pass the same filters before being merged back;
                    // otherwise every local CRM marker reappears after a phase is chosen.
                    val confirmedServer = serverContacts.filter { contact ->
                        CrmContactSyncStore.isEnabled(appContext, contact.number)
                    }
                    val filteredLocalAfterSync = filteredLocalCrmContacts(appContext, filterState)
                    (confirmedServer + filteredLocalAfterSync)
                        .distinctBy { contact -> HomeCallPageLoader.noteKey(contact.number) }
                } else {
                    serverContacts
                }
                val page = pageContacts(contacts, requestedPage, pageSize)
                val serverNotes = HomeCrmClientServerNotes.snapshot(appContext, page)
                val contactNotes = HomeCallPageLoader.contactNotes(appContext, page).toMutableMap().apply {
                    putAll(serverNotes.contactNotesByNumber)
                }
                HomeRenderData(
                    calls = page,
                    contactNotesByNumber = contactNotes,
                    contactNamesByNumber = page.associate { call ->
                        HomeCallPageLoader.noteKey(call.number) to call.displayName
                    },
                    callNotesByCall = serverNotes.callNotesByCall,
                )
            }

            handler.post {
                finishBusy(busyToken)
                if (!isCurrent(expectedGeneration, requestedPage, filterState)) return@post
                finalResult.onSuccess { data ->
                    if (data.calls.isEmpty()) contactsContent.renderEmpty(pageSize)
                    else contactsContent.render(data, pageSize)
                }.onFailure {
                    // Offline or temporary server failure must not erase the fast,
                    // already phase-filtered local CRM list shown on screen.
                    if (!provisionalAvailable) contactsContent.renderEmpty(pageSize)
                }
                onRenderComplete()
            }
        }
    }

    /** Applies the exact Clients phase/company rules to the local CRM fallback. */
    private fun filteredLocalCrmContacts(
        context: Context,
        filterState: HomeCrmFilterState,
    ): List<PhoneCallRecord> {
        val local = HomeCrmContactCandidates.loadLocal(context)
        val phaseFiltered = HomeCrmFilterEngine.filterLocal(context, local, filterState)
        if (!filterState.isCompanyFiltered || phaseFiltered.isEmpty()) return phaseFiltered
        val memberships = HomeCrmCompanyMembershipStore.resolve(
            context = context.applicationContext,
            config = ConfigStore.load(context.applicationContext),
            phones = phaseFiltered.map { it.number },
        )
        return HomeCrmFilterEngine.filterByCompany(
            calls = phaseFiltered,
            state = filterState,
            companyIdsByPhoneKey = memberships.companyIdsByPhoneKey,
        )
    }

    private fun pageContacts(
        contacts: List<PhoneCallRecord>,
        requestedPage: Int,
        pageSize: Int,
    ): List<PhoneCallRecord> = contacts
        .map { contact -> enrichWithLocalName(contact) }
        .sortedWith(contactListOrder)
        .drop(requestedPage * pageSize)
        .take(pageSize)

    private fun provisionalData(page: List<PhoneCallRecord>): HomeRenderData = HomeRenderData(
        calls = page,
        contactNotesByNumber = emptyMap(),
        contactNamesByNumber = page.associate { call ->
            HomeCallPageLoader.noteKey(call.number) to call.displayName
        },
        callNotesByCall = emptyMap(),
    )

    private fun isCurrent(
        expectedGeneration: Int,
        requestedPage: Int,
        filterState: HomeCrmFilterState,
    ): Boolean = expectedGeneration == generation.get() &&
        !activity.isFinishing &&
        !activity.isDestroyed &&
        isCrmContactsMode() &&
        activePhoneFilter().isBlank() &&
        activeSearchQuery().isBlank() &&
        pageIndex() == requestedPage &&
        crmFilters.state() == filterState

    private fun finishBusy(token: Long) {
        busyTokens.remove(token)
        HomeBusyTooltipUi.end(activity, token)
    }

    private fun finishAllBusy() {
        busyTokens.toList().forEach(::finishBusy)
    }

    private fun enrichWithLocalName(contact: PhoneCallRecord): PhoneCallRecord {
        val localName = ContactGroupFilter.resolveDisplayName(activity.applicationContext, contact.number).orEmpty().trim()
        if (localName.isBlank()) return contact
        return contact.copy(name = localName)
    }

    private companion object {
        /** Saved contacts stay alphabetic; unsaved/server-only leads follow, newest activity first. */
        val contactListOrder = Comparator<PhoneCallRecord> { left, right ->
            val leftUnknownLead = left.name.isBlank() && left.startedAt > 0L
            val rightUnknownLead = right.name.isBlank() && right.startedAt > 0L
            when {
                leftUnknownLead != rightUnknownLead -> if (leftUnknownLead) 1 else -1
                leftUnknownLead -> right.startedAt.compareTo(left.startedAt)
                else -> String.CASE_INSENSITIVE_ORDER.compare(
                    left.displayName.ifBlank { left.number },
                    right.displayName.ifBlank { right.number },
                )
            }
        }
    }
}
