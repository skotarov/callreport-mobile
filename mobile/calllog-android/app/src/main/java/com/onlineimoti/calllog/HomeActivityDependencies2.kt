package com.onlineimoti.calllog

internal fun HomeActivity.createHomeCallRowRenderer(): HomeCallRowRenderer = run {
    HomeCallRowRenderer(
        this,
        uiGeometry::dp,
        HomeCallPageLoader::noteKey,
        uiGeometry::roundedRect,
        homeActions::openContactNotesScreen,
        homeActions::openContactNotePopupForCall,
        homeActions::openDialer,
    )
}
internal fun HomeActivity.createEdgePaging(): HomeEdgePagingController = run {
    HomeEdgePagingController(
        binding = binding,
        canPrevious = { timelineCoordinator.isOnLaterPage() },
        canNext = { binding.nextCallsButton.isEnabled },
        previousPage = { timelineCoordinator.previousPage() },
        nextPage = { timelineCoordinator.nextPage() },
    )
}
internal fun HomeActivity.createCrmContactsContentView(): HomeCrmContactsContentView = run {
    HomeCrmContactsContentView(
        this,
        binding,
        { pageIndex },
        homeContentRenderer,
        companyGeneralNotesController,
        crmContactRowRenderer,
        crmTimelineToggle,
        { crmFiltersController.hasActiveFilters() },
        edgePaging::isTransitioning,
    )
}
internal fun HomeActivity.createCallsLoader(): HomeCallsLoader = run {
    HomeCallsLoader(
        this,
        handler,
        homeContentRenderer,
        crmFiltersController,
        serverCallNotesController,
        { "" },
        { activeSearchQuery },
        { pageIndex },
        this::isCrmModeEnabled,
        pullRefreshController::complete,
        onCrmCallsRendered = { count -> crmTimelineToggle.showRange(false, pageIndex, pageSize(), count) },
        onCrmCallsEmpty = { crmTimelineToggle.showEmpty(false) },
    )
}
internal fun HomeActivity.createSearchInputController(): HomeSearchInputController = run {
    HomeSearchInputController(
        this,
        binding,
        handler,
        { query ->
            edgePaging.cancel()
            activeSearchQuery = query
            pageIndex = 0
            renderCalls()
        },
        {
            edgePaging.cancel()
            activeSearchQuery = ""
            pageIndex = 0
            renderCalls()
        },
    )
}
internal fun HomeActivity.createRuntimeController(): HomeActivityRuntimeController = run {
    HomeActivityRuntimeController(
        activity = this,
        binding = { binding },
        refreshExecutor = refreshExecutor,
        isCrmContactsMode = this::isCrmContactsMode,
        isServerReady = this::isServerReady,
        clearSearchCache = HomeCallPageLoader::clearSearchCache,
        invalidateCompanyNotes = companyGeneralNotesController::invalidate,
        invalidateCrmContacts = crmContactsContentView::invalidate,
        refreshCompanies = { force -> crmFiltersController.refreshCompaniesIfNeeded(force = force) },
        resetTimelineForRefresh = this::resetTimelineForRefresh,
        scheduleSettledCallLogRefresh = callLogObserver::scheduleSettledRefresh,
        renderCalls = this::renderCalls,
    )
}
