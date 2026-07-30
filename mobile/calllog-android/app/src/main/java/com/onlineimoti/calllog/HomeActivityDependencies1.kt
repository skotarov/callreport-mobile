package com.onlineimoti.calllog

internal fun HomeActivity.createCrmFiltersController(): HomeCrmFiltersController = run {
    HomeCrmFiltersController(this, binding, handler, uiGeometry::dp, uiGeometry::roundedRect) {
        edgePaging.cancel()
        homeContentRenderer.clearCalls()
        crmContactsContentView.invalidate()
        pageIndex = 0
        companyGeneralNotesController.invalidate()
        renderCalls()
    }
}
internal fun HomeActivity.createCrmContactRowRenderer(): HomeCrmContactRowRenderer = run {
    HomeCrmContactRowRenderer(
        this,
        uiGeometry::dp,
        uiGeometry::roundedRect,
        filteredContactSummaryChipsUi,
        homeActions::openContactNotesScreen,
        homeActions::openDialer,
    )
}
internal fun HomeActivity.createHomeContentRenderer(): HomeContentRenderer = run {
    HomeContentRenderer(
        activity = this,
        binding = binding,
        activeSearchQuery = { activeSearchQuery },
        pageIndex = { pageIndex },
        isCrmModeEnabled = this::isCrmModeEnabled,
        isCrmContactsMode = this::isCrmContactsMode,
        hasActiveCrmFilters = { crmFiltersController.hasActiveFilters() },
        dp = uiGeometry::dp,
        rowRenderer = homeCallRowRenderer,
        companyGeneralNotes = companyGeneralNotesController,
        retainRowsDuringEdgePaging = edgePaging::isTransitioning,
    )
}
internal fun HomeActivity.createCrmContactsLoader(): HomeCrmContactsLoader = run {
    HomeCrmContactsLoader(
        this,
        handler,
        crmContactsContentView,
        crmFiltersController,
        { "" },
        { activeSearchQuery },
        { pageIndex },
        this::isServerReady,
        this::isCrmContactsMode,
        pullRefreshController::complete,
    )
}
internal fun HomeActivity.createSearchController(): HomeSearchController = run {
    HomeSearchController(
        this,
        binding,
        handler,
        searchExecutor,
        searchGeneration,
        serverCallNotesController,
        this::pageSize,
        { "" },
        { activeSearchQuery },
        this::isCrmModeEnabled,
        { pageIndex },
        homeContentRenderer::replaceCurrentCalls,
        homeContentRenderer::renderEmptyState,
        homeContentRenderer::applyRenderData,
        pullRefreshController::complete,
    )
}
internal fun HomeActivity.createTimelineCoordinator(): HomeTimelineCoordinator = run {
    HomeTimelineCoordinator(
        activity = this,
        callsLoader = callsLoader,
        contactsLoader = crmContactsLoader,
        serverCallNotes = serverCallNotesController,
        searchController = searchController,
        contentRenderer = homeContentRenderer,
        crmFilters = crmFiltersController,
        pullRefresh = pullRefreshController,
        timelineToggle = crmTimelineToggle,
        activeSearchQuery = { activeSearchQuery },
        pageIndex = { pageIndex },
        setPageIndex = { pageIndex = it },
        pageSize = this::pageSize,
        isCrmModeEnabled = this::isCrmModeEnabled,
        isCrmContactsMode = this::isCrmContactsMode,
        setCrmContactsMode = { crmContactsMode = it },
        onCrmModeChanged = {
            companyGeneralNotesController.invalidate()
            crmContactsContentView.invalidate()
            runtimeController.updateHeader()
        },
    )
}
