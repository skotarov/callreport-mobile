package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HomeActivity : FontScaledAppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val handler = Handler(Looper.getMainLooper())
    private val uiGeometry: HomeUiGeometry by lazy { HomeUiGeometry(resources) }
    private val searchExecutor = Executors.newFixedThreadPool(2)
    private val refreshExecutor = Executors.newSingleThreadExecutor()
    private val searchGeneration = AtomicInteger(0)
    private var pageIndex = 0
    private var activeSearchQuery = ""
    private var crmContactsMode = false
    private var initialResumePending = true
    private var homeIsResumed = false
    private var refreshWhenResumed = false

    private val contactsSyncPreparer: HomeContactsSyncPreparer by lazy { HomeContactsSyncPreparer(this) }
    private val noteSavedReceiver: HomeNoteSavedReceiverController by lazy {
        HomeNoteSavedReceiverController(this) {
            HomeCallPageLoader.clearSearchCache()
            companyGeneralNotesController.invalidate()
            crmContactsContentView.invalidate()
            HomeCrmPhaseLookup.invalidate()
            if (homeIsResumed) renderCalls() else refreshWhenResumed = true
        }
    }
    private val callLogObserver: HomeCallLogObserverController by lazy {
        HomeCallLogObserverController(this, handler, ::onCallLogChanged)
    }
    private val noteRefreshController: HomeNoteRefreshController by lazy {
        HomeNoteRefreshController(
            handler,
            {
                HomeCallPageLoader.clearSearchCache()
                companyGeneralNotesController.invalidate()
                crmContactsContentView.invalidate()
            },
            ::renderCalls,
        )
    }
    private val homeActions: HomeActions by lazy {
        HomeActions(this, binding, noteRefreshController::start) { activeSearchQuery.isBlank() }
    }
    private val crmTimelineToggle: HomeCrmTimelineModeToggle by lazy {
        HomeCrmTimelineModeToggle(this, binding, uiGeometry::dp) {
            timelineCoordinator.toggleCrmCallLogFromOverflow()
        }
    }
    private val companyGeneralNotesController: HomeCompanyGeneralNotesController by lazy {
        HomeCompanyGeneralNotesController(this, handler) {
            if (::binding.isInitialized && !isFinishing && !isDestroyed) {
                if (isCrmContactsMode()) crmContactsContentView.renderCurrentRowsAfterCompanyLabels(pageSize())
                else homeContentRenderer.renderCurrentRowsAfterCompanyLabels(pageSize())
            }
        }
    }
    private val serverCallNotesController: HomeServerCallNotesController by lazy {
        HomeServerCallNotesController(this, handler)
    }
    private val crmFiltersController: HomeCrmFiltersController by lazy {
        HomeCrmFiltersController(this, binding, handler, uiGeometry::dp, uiGeometry::roundedRect) {
            edgePaging.cancel()
            homeContentRenderer.clearCalls()
            crmContactsContentView.invalidate()
            pageIndex = 0
            companyGeneralNotesController.invalidate()
            renderCalls()
        }
    }
    private val filteredContactSummaryChipsUi: HomeCompanyScopeChipsUi by lazy {
        HomeCompanyScopeChipsUi(this, uiGeometry::dp, uiGeometry::roundedRect)
    }
    private val homeCallRowRenderer: HomeCallRowRenderer by lazy {
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
    private val crmContactRowRenderer: HomeCrmContactRowRenderer by lazy {
        HomeCrmContactRowRenderer(
            this,
            uiGeometry::dp,
            uiGeometry::roundedRect,
            filteredContactSummaryChipsUi,
            homeActions::openContactNotesScreen,
            homeActions::openDialer,
        )
    }
    private val edgePaging: HomeEdgePagingController by lazy {
        HomeEdgePagingController(
            binding = binding,
            canPrevious = { timelineCoordinator.isOnLaterPage() },
            canNext = { binding.nextCallsButton.isEnabled },
            previousPage = { timelineCoordinator.previousPage() },
            nextPage = { timelineCoordinator.nextPage() },
        )
    }
    private val homeContentRenderer: HomeContentRenderer by lazy {
        HomeContentRenderer(
            activity = this,
            binding = binding,
            activeSearchQuery = { activeSearchQuery },
            pageIndex = { pageIndex },
            isCrmModeEnabled = ::isCrmModeEnabled,
            isCrmContactsMode = ::isCrmContactsMode,
            hasActiveCrmFilters = { crmFiltersController.hasActiveFilters() },
            dp = uiGeometry::dp,
            rowRenderer = homeCallRowRenderer,
            companyGeneralNotes = companyGeneralNotesController,
            retainRowsDuringEdgePaging = edgePaging::isTransitioning,
        )
    }
    private val crmContactsContentView: HomeCrmContactsContentView by lazy {
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
    private val pullRefreshController: HomePullRefreshController by lazy {
        HomePullRefreshController(binding, handler)
    }
    private val callsLoader: HomeCallsLoader by lazy {
        HomeCallsLoader(
            this,
            handler,
            homeContentRenderer,
            crmFiltersController,
            serverCallNotesController,
            { "" },
            { activeSearchQuery },
            { pageIndex },
            ::isCrmModeEnabled,
            pullRefreshController::complete,
            onCrmCallsRendered = { count -> crmTimelineToggle.showRange(false, pageIndex, pageSize(), count) },
            onCrmCallsEmpty = { crmTimelineToggle.showEmpty(false) },
        )
    }
    private val crmContactsLoader: HomeCrmContactsLoader by lazy {
        HomeCrmContactsLoader(
            this,
            handler,
            crmContactsContentView,
            crmFiltersController,
            { "" },
            { activeSearchQuery },
            { pageIndex },
            ::isServerReady,
            ::isCrmContactsMode,
            pullRefreshController::complete,
        )
    }
    private val searchController: HomeSearchController by lazy {
        HomeSearchController(
            this,
            binding,
            handler,
            searchExecutor,
            searchGeneration,
            serverCallNotesController,
            ::pageSize,
            { "" },
            { activeSearchQuery },
            ::isCrmModeEnabled,
            { pageIndex },
            homeContentRenderer::replaceCurrentCalls,
            homeContentRenderer::renderEmptyState,
            homeContentRenderer::applyRenderData,
            pullRefreshController::complete,
        )
    }
    private val searchInputController: HomeSearchInputController by lazy {
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
    private val timelineCoordinator: HomeTimelineCoordinator by lazy {
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
            pageSize = ::pageSize,
            isCrmModeEnabled = ::isCrmModeEnabled,
            isCrmContactsMode = ::isCrmContactsMode,
            setCrmContactsMode = { crmContactsMode = it },
            onCrmModeChanged = {
                companyGeneralNotesController.invalidate()
                crmContactsContentView.invalidate()
                runtimeController.updateHeader()
            },
        )
    }
    private val runtimeController: HomeActivityRuntimeController by lazy {
        HomeActivityRuntimeController(
            activity = this,
            binding = { binding },
            refreshExecutor = refreshExecutor,
            isCrmContactsMode = ::isCrmContactsMode,
            isServerReady = ::isServerReady,
            clearSearchCache = HomeCallPageLoader::clearSearchCache,
            invalidateCompanyNotes = companyGeneralNotesController::invalidate,
            invalidateCrmContacts = crmContactsContentView::invalidate,
            refreshCompanies = { force -> crmFiltersController.refreshCompaniesIfNeeded(force = force) },
            resetTimelineForRefresh = ::resetTimelineForRefresh,
            scheduleSettledCallLogRefresh = callLogObserver::scheduleSettledRefresh,
            renderCalls = ::renderCalls,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        if (DistributionCapabilities.isPlayBusinessBuild && !CorporateAccess.isActive(this)) {
            startActivity(Intent(this, CompanyAccountActivity::class.java).apply {
                putExtra(CompanyAccountActivity.EXTRA_MODE, CompanyAccountActivity.MODE_LOGIN)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
            return
        }
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        HomeResumeRefreshController.install(this, binding)
        edgePaging.bind()
        noteSavedReceiver.register()
        callLogObserver.register()
        crmContactsMode = DistributionCapabilities.isPlayBusinessBuild
        binding.crmContactsBackButton.setOnClickListener {
            edgePaging.cancel()
            timelineCoordinator.returnToCallLog()
        }
        crmTimelineToggle
        runtimeController.updateHeader()
        crmFiltersController.updateVisibility(isCrmModeEnabled() || isCrmContactsMode())
        homeContentRenderer.prepareForRender(pageSize(), keepExistingRows = false)
        searchInputController.bind()
        pullRefreshController.bind(runtimeController::refreshFromPull)
        HomeScreenActionBinder.wire(
            activity = this,
            binding = binding,
            openOverflow = { HomeOverflowMenu.show(this, binding.settingsButton) { homeActions.openSettings() } },
            openCrmContacts = timelineCoordinator::toggleCrmContactsMode,
            previousPage = timelineCoordinator::previousPage,
            nextPage = timelineCoordinator::nextPage,
            isOnLaterPage = timelineCoordinator::isOnLaterPage,
            goToFirstPage = timelineCoordinator::goToFirstPage,
        )
        if (DistributionCapabilities.isPlayBusinessBuild) binding.crmModeButton.visibility = View.GONE
    }

    override fun onBackPressed() {
        edgePaging.cancel()
        if (timelineCoordinator.returnToCallLog()) return
        super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        edgePaging.cancel()
        activeSearchQuery = ""
        pageIndex = 0
        companyGeneralNotesController.invalidate()
        crmContactsContentView.invalidate()
        searchInputController.resetText()
        renderCalls()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        homeIsResumed = true
        callLogObserver.register()
        contactsSyncPreparer.prepareOnce()
        crmFiltersController.refreshCompaniesIfNeeded()
        edgePaging.bind()
        if (initialResumePending) {
            initialResumePending = false
            HomeCallPageLoader.clearSearchCache()
            companyGeneralNotesController.invalidate()
            crmContactsContentView.invalidate()
            HomeCrmPhaseLookup.invalidate()
            renderCalls()
        } else if (refreshWhenResumed) {
            refreshWhenResumed = false
            renderCalls()
        } else {
            runtimeController.updateHeader()
        }
    }

    override fun onPause() {
        homeIsResumed = false
        pullRefreshController.cancel()
        noteRefreshController.cancel()
        searchInputController.cancelPending()
        searchController.cancelActiveTask()
        super.onPause()
    }

    override fun onDestroy() {
        noteSavedReceiver.unregister()
        callLogObserver.unregister()
        searchGeneration.incrementAndGet()
        edgePaging.release()
        pullRefreshController.cancel()
        searchController.cancelActiveTask()
        searchExecutor.shutdownNow()
        refreshExecutor.shutdownNow()
        callsLoader.release()
        crmContactsLoader.release()
        serverCallNotesController.release()
        crmFiltersController.release()
        companyGeneralNotesController.release()
        contactsSyncPreparer.release()
        super.onDestroy()
    }

    private fun onCallLogChanged() {
        edgePaging.cancel()
        HomeCallPageLoader.clearSearchCache()
        HomeTimelineLoader.invalidateCache()
        companyGeneralNotesController.invalidate()
        if (activeSearchQuery.isBlank() && !isCrmContactsMode()) resetTimelineForRefresh()
        if (homeIsResumed) renderCalls() else refreshWhenResumed = true
    }

    private fun resetTimelineForRefresh() {
        edgePaging.cancel()
        pageIndex = 0
        homeContentRenderer.clearCalls()
    }

    private fun renderCalls() {
        runtimeController.updateHeader()
        timelineCoordinator.renderCalls()
    }

    private fun isCrmModeEnabled(): Boolean = HomeCrmModeStore.isEnabled(this)
    private fun isServerReady(): Boolean = CallReportRemoteAccess.isReady(ConfigStore.load(this))
    private fun isCrmContactsMode(): Boolean = DistributionCapabilities.isPlayBusinessBuild || crmContactsMode
    private fun pageSize(): Int = ConfigStore.load(this).homeCallPageSize.coerceIn(5, 100)

    companion object {
        const val ACTION_CONTACT_NOTE_SAVED = "com.onlineimoti.calllog.CONTACT_NOTE_SAVED"
    }
}
