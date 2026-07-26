package com.onlineimoti.calllog

import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads the authenticated user's Clients page from the server. Local Contacts and
 * local notes are used only as display enrichment after the server has selected
 * the broker/profile and company scope. Effective local + server phase state is
 * applied before pagination so a just-edited phase does not disappear while sync
 * is still pending.
 */
internal class HomeCrmContactsLoader(
    private val activity: HomeActivity,
    private val handler: Handler,
    private val contactsContent: HomeCrmContactsContentView,
    private val crmFilters: HomeCrmFiltersController,
    private val activePhoneFilter: () -> String,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    private val isCrmModeEnabled: () -> Boolean,
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
            val data = runCatching {
                val candidates = HomeCrmContactCandidates.load(appContext, filterState)
                val contacts = crmFilters.filterSearchResults(candidates)
                val page = contacts
                    .map { contact -> enrichWithLocalName(contact) }
                    .sortedWith(contactListOrder)
                    .drop(requestedPage * pageSize)
                    .take(pageSize)
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
            }.getOrDefault(HomeRenderData(emptyList(), emptyMap(), emptyMap()))
            handler.post {
                finishBusy(busyToken)
                val current = expectedGeneration == generation.get() &&
                    !activity.isFinishing &&
                    !activity.isDestroyed &&
                    // The Clients page is server-backed and does not depend on the
                    // old local CRM call-log mode. Requiring isCrmModeEnabled() here
                    // left the screen stuck on "Loading customers" after opening
                    // Clients directly from the toolbar/overflow.
                    isCrmContactsMode() &&
                    activePhoneFilter().isBlank() &&
                    activeSearchQuery().isBlank() &&
                    pageIndex() == requestedPage &&
                    crmFilters.state() == filterState
                if (!current) return@post
                if (data.calls.isEmpty()) contactsContent.renderEmpty(pageSize)
                else contactsContent.render(data, pageSize)
                onRenderComplete()
            }
        }
    }

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
