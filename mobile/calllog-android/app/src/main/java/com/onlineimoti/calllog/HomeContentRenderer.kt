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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders the ordinary Call Log, search results and CRM call rows. */
internal class HomeContentRenderer(
    internal val activity: AppCompatActivity,
    internal val binding: ActivityHomeBinding,
    internal val activeSearchQuery: () -> String,
    internal val pageIndex: () -> Int,
    internal val isCrmModeEnabled: () -> Boolean,
    internal val isCrmContactsMode: () -> Boolean,
    internal val hasActiveCrmFilters: () -> Boolean,
    internal val dp: (Int) -> Int,
    internal val rowRenderer: HomeCallRowRenderer,
    internal val companyGeneralNotes: HomeCompanyGeneralNotesController,
    internal val retainRowsDuringEdgePaging: () -> Boolean = { false },
) {
    var currentCalls: List<PhoneCallRecord> = emptyList()
        internal set

    internal var currentContactNotesByNumber: Map<String, String> = emptyMap()
    internal var currentContactNamesByNumber: Map<String, String> = emptyMap()
    internal var currentCallNotesByCall: Map<String, HomeCallNote> = emptyMap()
    internal var currentCompanyLabelsByNumber: Map<String, List<HomeCompanyScopeLabel>> = emptyMap()
    internal var currentServerBackedPhoneKeys: Set<String> = emptySet()
    internal val rememberedContactNamesByNumber = linkedMapOf<String, String>()

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
        if (unchanged && !forceRender) {
            HomeLoadingFooterUi.hide(binding.homeCallsContainer)
            return
        }

        val page = HomePagedListUi.page(
            binding.homeCallsContainer,
            PageLoadingModeStore.usesPrefetch(activity),
            pageIndex(),
        )
        val patched = !forceRender && calls == previousCalls && page.childCount > 0 && patchChangedRows(
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

}
