package com.onlineimoti.calllog

/** Coordinates Home paging, call loading, CRM calls and CRM contacts. */
internal class HomeTimelineCoordinator(
    private val activity: HomeActivity,
    private val callsLoader: HomeCallsLoader,
    private val contactsLoader: HomeCrmContactsLoader,
    private val serverCallNotes: HomeServerCallNotesController,
    private val searchController: HomeSearchController,
    private val contentRenderer: HomeContentRenderer,
    private val crmFilters: HomeCrmFiltersController,
    private val pullRefresh: HomePullRefreshController,
    private val timelineToggle: HomeCrmTimelineModeToggle,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    private val setPageIndex: (Int) -> Unit,
    private val pageSize: () -> Int,
    private val isCrmModeEnabled: () -> Boolean,
    private val isCrmContactsMode: () -> Boolean,
    private val setCrmContactsMode: (Boolean) -> Unit,
    private val onCrmModeChanged: () -> Unit,
) {
    fun renderCalls() {
        if (HomeRefreshRenderPolicy.consumeForceRebuild()) {
            // Android can discard the rendered page while this coordinator still
            // holds the same call model. Clearing the model guarantees that an
            // equal authoritative reload rebuilds the views instead of returning early.
            contentRenderer.clearCalls()
        }
        val callsGeneration = callsLoader.invalidate()
        val contactsGeneration = contactsLoader.invalidate()
        serverCallNotes.cancelPending()
        if (activeSearchQuery().isBlank()) searchController.cancelActiveTask()
        val size = pageSize()
        val crmEnabled = isCrmModeEnabled()
        val remoteReady = CallReportRemoteAccess.isReady(ConfigStore.load(activity))
        val contactsMode = remoteReady && isCrmContactsMode()
        timelineToggle.prepare(remoteReady, contactsMode)
        val showCrmFilters = crmEnabled || contactsMode
        crmFilters.updateVisibility(showCrmFilters)
        val retainLoadedCallLog = HomeRefreshRenderPolicy.consumeKeepExistingRows() &&
            activeSearchQuery().isBlank() && !crmEnabled && !contactsMode
        contentRenderer.prepareForRender(
            size,
            keepExistingRows = retainLoadedCallLog || showCrmFilters || pullRefresh.isInProgress(),
        )
        if (!contactsMode && !PhoneCallReader.hasCallLogPermission(activity)) {
            contentRenderer.showMissingCallLogPermission()
            pullRefresh.complete()
            return
        }
        when {
            activeSearchQuery().isNotBlank() -> searchController.renderSearchCallsAsync()
            contactsMode -> contactsLoader.renderAsync(size, contactsGeneration)
            crmEnabled -> callsLoader.renderCrmCallsAsync(size, callsGeneration)
            else -> callsLoader.renderLocalCalls(size)
        }
    }

    fun previousPage() {
        if (pageIndex() <= 0) return
        setPageIndex(pageIndex() - 1)
        renderCalls()
    }

    fun nextPage() {
        if (contentRenderer.currentCalls.size < pageSize()) return
        setPageIndex(pageIndex() + 1)
        renderCalls()
    }

    fun isOnLaterPage(): Boolean = pageIndex() > 0

    fun goToFirstPage() {
        if (pageIndex() <= 0) return
        setPageIndex(0)
        renderCalls()
    }

    fun setCrmMode(enabled: Boolean) {
        if (DistributionCapabilities.isPlayBusinessBuild || !HomeCrmModeStore.setEnabled(activity, enabled)) return
        contentRenderer.clearCalls()
        setPageIndex(0)
        onCrmModeChanged()
        renderCalls()
    }

    /** Opens CRM calls from the overflow menu; it is now a link, not an on/off toggle. */
    fun toggleCrmCallLogFromOverflow() {
        if (DistributionCapabilities.isPlayBusinessBuild || !HomeCrmModeStore.isAvailable(activity)) return
        if (isCrmContactsMode()) {
            setCrmContactsMode(false)
            setCrmMode(true)
            return
        }
        if (!isCrmModeEnabled()) setCrmMode(true)
    }

    fun toggleCrmContactsMode() {
        if (DistributionCapabilities.isPlayBusinessBuild || !CallReportRemoteAccess.isReady(ConfigStore.load(activity))) return
        if (isCrmContactsMode()) {
            returnToCallLog()
            return
        }
        setCrmContactsMode(true)
        contentRenderer.clearCalls()
        setPageIndex(0)
        onCrmModeChanged()
        renderCalls()
    }

    /** Returns from CRM contacts or CRM calls to the regular Home call log. */
    fun returnToCallLog(): Boolean {
        if (DistributionCapabilities.isPlayBusinessBuild) return false
        if (isCrmContactsMode()) {
            setCrmContactsMode(false)
            contentRenderer.clearCalls()
            setPageIndex(0)
            onCrmModeChanged()
            renderCalls()
            return true
        }
        if (isCrmModeEnabled()) {
            setCrmMode(false)
            return true
        }
        return false
    }
}
