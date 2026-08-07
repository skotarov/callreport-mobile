package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Renders the ordinary Call Log, search results and CRM call rows. */
internal class HomeContentRenderer(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    private val isCrmModeEnabled: () -> Boolean,
    private val isCrmContactsMode: () -> Boolean,
    private val hasActiveCrmFilters: () -> Boolean,
    private val dp: (Int) -> Int,
    private val rowRenderer: HomeCallRowRenderer,
    private val companyGeneralNotes: HomeCompanyGeneralNotesController,
    private val retainRowsDuringEdgePaging: () -> Boolean = { false },
) {
    var currentCalls: List<PhoneCallRecord> = emptyList()
        private set

    private var currentContactNotesByNumber: Map<String, String> = emptyMap()
    private var currentContactNamesByNumber: Map<String, String> = emptyMap()
    private var currentCallNotesByCall: Map<String, HomeCallNote> = emptyMap()
    private var currentCompanyLabelsByNumber: Map<String, List<HomeCompanyScopeLabel>> = emptyMap()
    private var currentServerBackedPhoneKeys: Set<String> = emptySet()
    private val rememberedContactNamesByNumber = linkedMapOf<String, String>()

    fun replaceCurrentCalls(calls: List<PhoneCallRecord>) {
        currentCalls = calls
    }

    fun clearCalls() {
        currentCalls = emptyList()
        currentContactNotesByNumber = emptyMap()
        currentContactNamesByNumber = emptyMap()
        currentCallNotesByCall = emptyMap()
        currentCompanyLabelsByNumber = emptyMap()
        currentServerBackedPhoneKeys = emptySet()
        HomePagedListUi.clear(binding.homeCallsContainer)
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
    }

    fun prepareForRender(pageSize: Int, keepExistingRows: Boolean) {
        binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, pageSize)
        binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, pageSize)
        val retainRows = keepExistingRows || retainRowsDuringEdgePaging()
        HomePagedListUi.prepare(
            binding.homeCallsContainer,
            PageLoadingModeStore.usesPrefetch(activity),
            pageIndex(),
            reset = !retainRows || currentCalls.isEmpty(),
        )
        binding.fullLogProgress.visibility = View.GONE
        binding.clearFilterButton.visibility = View.GONE
        binding.filteredDialButton.visibility = View.GONE
        binding.filteredContactSummaryContainer.visibility = View.GONE
        updateCrmModeControls()
        updateStatusStyle(hidePlainTimelineRange = true)
    }

    fun showLoading() {
        binding.fullLogProgress.visibility = View.GONE
        HomeLoadingFooterUi.show(binding.homeCallsContainer)
        binding.paginationContainer.visibility = View.GONE
        if (currentCalls.isEmpty()) {
            binding.homeStatusText.text = activity.getString(R.string.runtime_crm_calls_loading)
        }
    }

    fun showMissingCallLogPermission() {
        val text = activity.getString(R.string.dynamic_home_missing_call_log_permission)
        if (isTopLevelCrmPage()) showResultsStatus(text)
        else {
            binding.homeStatusText.text = text
            updateStatusStyle()
        }
        binding.fullLogProgress.visibility = View.GONE
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
        binding.paginationContainer.visibility = View.GONE
    }

    fun showCrmLoading() {
        if (retainRowsDuringEdgePaging()) HomeLoadingFooterUi.show(binding.homeCallsContainer)
        else showResultsStatus(activity.getString(R.string.runtime_crm_calls_loading))
        binding.paginationContainer.visibility = View.GONE
    }

    fun applyRenderData(renderData: HomeRenderData, pageSize: Int) = applyRenderData(
        renderData,
        pageSize,
        refreshCompanyLabels = true,
        mergeMode = HomeRenderMergeMode.AUTHORITATIVE,
    )

    fun applyProvisionalRenderData(renderData: HomeRenderData, pageSize: Int) = applyRenderData(
        renderData,
        pageSize,
        refreshCompanyLabels = true,
        mergeMode = HomeRenderMergeMode.PROVISIONAL,
    )

    fun applySupplementalRenderData(renderData: HomeRenderData, pageSize: Int) = applyRenderData(
        renderData,
        pageSize,
        refreshCompanyLabels = true,
        mergeMode = HomeRenderMergeMode.SUPPLEMENTAL,
    )

    fun renderCurrentRowsAfterCompanyLabels(pageSize: Int) {
        if (currentCalls.isEmpty()) return
        applyRenderData(
            HomeRenderData(
                currentCalls,
                currentContactNotesByNumber,
                currentContactNamesByNumber,
                currentCallNotesByCall,
            ),
            pageSize,
            refreshCompanyLabels = false,
            mergeMode = HomeRenderMergeMode.AUTHORITATIVE,
        )
    }

    fun renderEmptyState() {
        binding.fullLogProgress.visibility = View.GONE
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
        val query = activeSearchQuery()
        val page = pageIndex()
        val message = when {
            query.isNotBlank() -> activity.getString(R.string.dynamic_home_no_search_results, query.trim())
            isCrmModeEnabled() && hasActiveCrmFilters() -> activity.getString(R.string.dynamic_home_no_crm_filter_results)
            page == 0 -> activity.getString(R.string.dynamic_home_no_calls)
            else -> activity.getString(R.string.dynamic_home_no_more_calls)
        }
        if (isTopLevelCrmPage()) showResultsStatus(message) else binding.homeStatusText.text = message
        updateStatusStyle()
        PaginationButtonAppearance.apply(binding.previousCallsButton, page > 0)
        PaginationButtonAppearance.apply(binding.nextCallsButton, false)
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, page + 1)
        binding.paginationContainer.visibility = if (PageLoadingModeStore.usesPrefetch(activity)) View.GONE else View.VISIBLE
    }

    private fun applyRenderData(
        data: HomeRenderData,
        pageSize: Int,
        refreshCompanyLabels: Boolean,
        mergeMode: HomeRenderMergeMode,
        forceRender: Boolean = false,
    ) {
        val calls = data.calls.sortedByDescending { it.startedAt }
        val state = HomeRenderStateMerger.merge(
            calls = calls,
            incoming = data,
            currentContactNotes = currentContactNotesByNumber,
            currentContactNames = currentContactNamesByNumber,
            currentCallNotes = currentCallNotesByCall,
            rememberedNames = rememberedContactNamesByNumber,
            mode = mergeMode,
        )
        val labels = companyGeneralNotes.labelsFor(calls)
        val serverBackedKeys = companyGeneralNotes.serverBackedPhoneKeysFor(calls)

        val previousCalls = currentCalls
        val previousContactNotes = currentContactNotesByNumber
        val previousContactNames = currentContactNamesByNumber
        val previousCallNotes = currentCallNotesByCall
        val previousCompanyLabels = currentCompanyLabelsByNumber
        val previousServerBackedKeys = currentServerBackedPhoneKeys
        val unchanged = calls == previousCalls &&
            state.contactNotesByNumber == previousContactNotes &&
            state.contactNamesByNumber == previousContactNames &&
            state.callNotesByCall == previousCallNotes &&
            labels == previousCompanyLabels &&
            serverBackedKeys == previousServerBackedKeys

        currentCalls = calls
        currentContactNotesByNumber = state.contactNotesByNumber
        currentContactNamesByNumber = state.contactNamesByNumber
        currentCallNotesByCall = state.callNotesByCall
        currentCompanyLabelsByNumber = labels
        currentServerBackedPhoneKeys = serverBackedKeys
        binding.fullLogProgress.visibility = View.GONE
        renderStatusAndPagination(pageSize)

        val automaticPaging = PageLoadingModeStore.usesPrefetch(activity)
        val page = HomePagedListUi.page(
            binding.homeCallsContainer,
            automaticPaging,
            pageIndex(),
        )
        val hasRenderedContent = if (automaticPaging) {
            page.childCount > 0
        } else {
            HomePagedListUi.visiblePageCount(binding.homeCallsContainer) > 0
        }
        if (!HomeRefreshRenderPolicy.shouldRebuildPage(unchanged, forceRender, hasRenderedContent)) {
            HomeLoadingFooterUi.hide(binding.homeCallsContainer)
            return
        }

        val patched = !forceRender && calls == previousCalls && hasRenderedContent && patchChangedRows(
            page = page,
            calls = calls,
            previousContactNotes = previousContactNotes,
            previousContactNames = previousContactNames,
            previousCallNotes = previousCallNotes,
            previousCompanyLabels = previousCompanyLabels,
            previousServerBackedKeys = previousServerBackedKeys,
            state = state,
            labels = labels,
            serverBackedKeys = serverBackedKeys,
        )
        if (!patched) rebuildPage(page, calls, state, labels, serverBackedKeys)

        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
        if (refreshCompanyLabels) companyGeneralNotes.refresh(calls)
    }

    /** Replaces only rows whose visible note/name state changed, preserving the list and scroll position. */
    private fun patchChangedRows(
        page: LinearLayout,
        calls: List<PhoneCallRecord>,
        previousContactNotes: Map<String, String>,
        previousContactNames: Map<String, String>,
        previousCallNotes: Map<String, HomeCallNote>,
        previousCompanyLabels: Map<String, List<HomeCompanyScopeLabel>>,
        previousServerBackedKeys: Set<String>,
        state: HomeRenderState,
        labels: Map<String, List<HomeCompanyScopeLabel>>,
        serverBackedKeys: Set<String>,
    ): Boolean {
        val changedCalls = calls.filter { call ->
            val phoneKey = HomeCallPageLoader.noteKey(call.number)
            val callKey = HomeCallNotesResolver.keyFor(call)
            previousContactNotes[phoneKey] != state.contactNotesByNumber[phoneKey] ||
                previousContactNames[phoneKey] != state.contactNamesByNumber[phoneKey] ||
                previousCallNotes[callKey] != state.callNotesByCall[callKey] ||
                previousCompanyLabels[phoneKey] != labels[phoneKey] ||
                (phoneKey in previousServerBackedKeys) != (phoneKey in serverBackedKeys)
        }
        if (changedCalls.isEmpty()) return true

        changedCalls.forEach { call ->
            val tagValue = rowTag(call)
            val index = (0 until page.childCount).firstOrNull { childIndex ->
                page.getChildAt(childIndex).tag == tagValue
            } ?: return false
            page.removeViewAt(index)
            page.addView(buildRow(call, state, labels, serverBackedKeys), index)
        }
        return true
    }

    private fun rebuildPage(
        page: LinearLayout,
        calls: List<PhoneCallRecord>,
        state: HomeRenderState,
        labels: Map<String, List<HomeCompanyScopeLabel>>,
        serverBackedKeys: Set<String>,
    ) {
        page.removeAllViews()
        val today = HomeTimelineDateUi.localDaySerial(System.currentTimeMillis()) ?: 0L
        var previousDay: Long? = null
        calls.forEach { call ->
            val day = HomeTimelineDateUi.localDaySerial(call.startedAt)
            if (day != null && day != previousDay) {
                page.addView(dateSeparator(call.startedAt, today - day, page.childCount > 0))
                previousDay = day
            }
            page.addView(buildRow(call, state, labels, serverBackedKeys))
        }
    }

    private fun buildRow(
        call: PhoneCallRecord,
        state: HomeRenderState,
        labels: Map<String, List<HomeCompanyScopeLabel>>,
        serverBackedKeys: Set<String>,
    ): View {
        val phoneKey = HomeCallPageLoader.noteKey(call.number)
        val displayName = state.contactNamesByNumber[phoneKey].orEmpty().ifBlank { call.displayName }
        val callNote = state.callNotesByCall[HomeCallNotesResolver.keyFor(call)]
        val row = rowRenderer.compactCallRow(
            call = call,
            displayName = displayName,
            contactNote = state.contactNotesByNumber[phoneKey],
            companyGeneralNoteLabels = labels[phoneKey],
            callNote = callNote,
            highlightQuery = activeSearchQuery(),
            showContactIdentity = true,
            showGeneralContactNote = true,
            serverBacked = phoneKey in serverBackedKeys,
        )
        return ListThemeUi.applyRowSpacing(row, dp).apply {
            tag = rowTag(call)
        }
    }

    private fun rowTag(call: PhoneCallRecord): String =
        "$HOME_ROW_TAG_PREFIX${HomeCallNotesResolver.keyFor(call)}"

    private fun dateSeparator(timestamp: Long, relativeDays: Long, hasRowsBefore: Boolean): TextView {
        val label = HomeTimelineDateUi.dayGroupLabel(activity, timestamp, relativeDays)
        return TextView(activity).apply {
            text = label
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(activity.getColor(R.color.callreport_icon_background))
            gravity = Gravity.CENTER_VERTICAL
            background = null
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = if (hasRowsBefore) dp(8) else 0
                bottomMargin = dp(4)
            }
        }
    }

    private fun renderStatusAndPagination(pageSize: Int) {
        val page = pageIndex()
        val query = activeSearchQuery()
        val start = page * pageSize + 1
        val end = page * pageSize + currentCalls.size
        binding.homeStatusText.text = if (query.isNotBlank()) {
            activity.getString(R.string.dynamic_home_status_search, query.trim(), start, end)
        } else {
            activity.getString(R.string.dynamic_home_status_calls, start, end)
        }
        updateStatusStyle(hidePlainTimelineRange = true)
        PaginationButtonAppearance.apply(binding.previousCallsButton, page > 0)
        PaginationButtonAppearance.apply(binding.nextCallsButton, currentCalls.size >= pageSize)
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, page + 1)
        binding.paginationContainer.visibility = if (PageLoadingModeStore.usesPrefetch(activity)) View.GONE else View.VISIBLE
    }

    private fun updateStatusStyle(hidePlainTimelineRange: Boolean = false) {
        val crmTopLevelStatusInResults = isTopLevelCrmPage()
        val plainCallLogRange = hidePlainTimelineRange && activeSearchQuery().isBlank() &&
            !isCrmModeEnabled() && !isCrmContactsMode()
        binding.homeStatusRow.visibility = if (plainCallLogRange) View.GONE else View.VISIBLE
        binding.homeStatusText.visibility = if (plainCallLogRange || crmTopLevelStatusInResults) View.GONE else View.VISIBLE
        binding.filteredStatusContainer.background = null
        binding.filteredStatusContainer.setPadding(0, 0, 0, 0)
        binding.homeStatusText.background = null
        binding.homeStatusText.setTextColor(Color.rgb(71, 85, 105))
        binding.homeStatusText.setPadding(0, 0, 0, 0)
    }

    private fun showResultsStatus(text: String) {
        currentCalls = emptyList()
        currentContactNotesByNumber = emptyMap()
        currentContactNamesByNumber = emptyMap()
        currentCallNotesByCall = emptyMap()
        currentCompanyLabelsByNumber = emptyMap()
        currentServerBackedPhoneKeys = emptySet()
        HomePagedListUi.clear(binding.homeCallsContainer)
        binding.fullLogProgress.visibility = View.GONE
        binding.homeStatusText.text = ""
        binding.homeStatusText.visibility = View.GONE
        binding.homeCallsContainer.addView(TextView(activity).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(dp(18), dp(28), dp(18), dp(28))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
    }

    private fun isTopLevelCrmPage(): Boolean = isCrmModeEnabled() || isCrmContactsMode()

    private fun updateCrmModeControls() {
        val showBrandShortcut = !isCrmContactsMode()
        HomeScreenActionBinder.updateBrandShortcutVisibility(binding, showBrandShortcut)
        val visible = HomeCrmModeStore.isAvailable(activity) && showBrandShortcut
        binding.crmControlsScroll.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val fill = Color.WHITE
        val border = Color.rgb(203, 213, 225)
        binding.crmModeButton.backgroundTintList = ColorStateList.valueOf(fill)
        binding.crmModeButton.strokeColor = ColorStateList.valueOf(border)
        binding.crmModeButton.setTextColor(Color.rgb(51, 65, 85))
    }

    private companion object {
        const val HOME_ROW_TAG_PREFIX = "relationship_manager_home_row:"
    }
}
