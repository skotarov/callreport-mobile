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
    internal lateinit var binding: ActivityHomeBinding
    internal val handler = Handler(Looper.getMainLooper())
    internal val uiGeometry: HomeUiGeometry by lazy { HomeUiGeometry(resources) }
    internal val searchExecutor = Executors.newFixedThreadPool(2)
    internal val refreshExecutor = Executors.newSingleThreadExecutor()
    internal val searchGeneration = AtomicInteger(0)
    internal var pageIndex = 0
    internal var activeSearchQuery = ""
    internal var crmContactsMode = false
    internal var initialResumePending = true
    internal var homeIsResumed = false
    internal var refreshWhenResumed = false

    internal val contactsSyncPreparer: HomeContactsSyncPreparer by lazy { HomeContactsSyncPreparer(this) }
    internal val noteSavedReceiver: HomeNoteSavedReceiverController by lazy {
        HomeNoteSavedReceiverController(this) {
            HomeCallPageLoader.clearSearchCache()
            companyGeneralNotesController.invalidate()
            crmContactsContentView.invalidate()
            HomeCrmPhaseLookup.invalidate()
            if (homeIsResumed) renderCalls() else refreshWhenResumed = true
        }
    }
    internal val callLogObserver: HomeCallLogObserverController by lazy {
        HomeCallLogObserverController(this, handler, ::onCallLogChanged)
    }
    internal val noteRefreshController: HomeNoteRefreshController by lazy {
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
    internal val homeActions: HomeActions by lazy {
        HomeActions(this, binding, noteRefreshController::start) { activeSearchQuery.isBlank() }
    }
    internal val crmTimelineToggle: HomeCrmTimelineModeToggle by lazy {
        HomeCrmTimelineModeToggle(this, binding, uiGeometry::dp) {
            timelineCoordinator.toggleCrmCallLogFromOverflow()
        }
    }
    internal val companyGeneralNotesController: HomeCompanyGeneralNotesController by lazy {
        HomeCompanyGeneralNotesController(this, handler) {
            if (::binding.isInitialized && !isFinishing && !isDestroyed) {
                if (isCrmContactsMode()) crmContactsContentView.renderCurrentRowsAfterCompanyLabels(pageSize())
                else homeContentRenderer.renderCurrentRowsAfterCompanyLabels(pageSize())
            }
        }
    }
    internal val serverCallNotesController: HomeServerCallNotesController by lazy {
        HomeServerCallNotesController(this, handler)
    }
    internal val crmFiltersController: HomeCrmFiltersController by lazy { createCrmFiltersController() }
    internal val filteredContactSummaryChipsUi: HomeCompanyScopeChipsUi by lazy {
        HomeCompanyScopeChipsUi(this, uiGeometry::dp, uiGeometry::roundedRect)
    }
    internal val homeCallRowRenderer: HomeCallRowRenderer by lazy { createHomeCallRowRenderer() }
    internal val crmContactRowRenderer: HomeCrmContactRowRenderer by lazy { createCrmContactRowRenderer() }
    internal val edgePaging: HomeEdgePagingController by lazy { createEdgePaging() }
    internal val homeContentRenderer: HomeContentRenderer by lazy { createHomeContentRenderer() }
    internal val crmContactsContentView: HomeCrmContactsContentView by lazy { createCrmContactsContentView() }
    internal val pullRefreshController: HomePullRefreshController by lazy {
        HomePullRefreshController(binding, handler)
    }
    internal val callsLoader: HomeCallsLoader by lazy { createCallsLoader() }
    internal val crmContactsLoader: HomeCrmContactsLoader by lazy { createCrmContactsLoader() }
    internal val searchController: HomeSearchController by lazy { createSearchController() }
    internal val searchInputController: HomeSearchInputController by lazy { createSearchInputController() }
    internal val timelineCoordinator: HomeTimelineCoordinator by lazy { createTimelineCoordinator() }
    internal val runtimeController: HomeActivityRuntimeController by lazy { createRuntimeController() }

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

    internal fun onCallLogChanged() {
        edgePaging.cancel()
        HomeCallPageLoader.clearSearchCache()
        HomeTimelineLoader.invalidateCache()
        companyGeneralNotesController.invalidate()
        if (activeSearchQuery.isBlank() && !isCrmContactsMode()) resetTimelineForRefresh()
        if (homeIsResumed) renderCalls() else refreshWhenResumed = true
    }

    internal fun resetTimelineForRefresh() {
        edgePaging.cancel()
        pageIndex = 0
        homeContentRenderer.clearCalls()
    }

    internal fun renderCalls() {
        runtimeController.updateHeader()
        timelineCoordinator.renderCalls()
    }

    internal fun isCrmModeEnabled(): Boolean = HomeCrmModeStore.isEnabled(this)
    internal fun isServerReady(): Boolean = CallReportRemoteAccess.isReady(ConfigStore.load(this))
    internal fun isCrmContactsMode(): Boolean = DistributionCapabilities.isPlayBusinessBuild || crmContactsMode
    internal fun pageSize(): Int = ConfigStore.load(this).homeCallPageSize.coerceIn(5, 100)

    companion object {
        const val ACTION_CONTACT_NOTE_SAVED = "com.onlineimoti.calllog.CONTACT_NOTE_SAVED"
    }
}
