package com.onlineimoti.calllog

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Draws the Clients list while retaining Home's existing paging controls. */
internal class HomeCrmContactsContentView(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val pageIndex: () -> Int,
    private val contentRenderer: HomeContentRenderer,
    private val companyGeneralNotes: HomeCompanyGeneralNotesController,
    private val rowRenderer: HomeCrmContactRowRenderer,
    private val timelineToggle: HomeCrmTimelineModeToggle,
    private val hasActiveCrmFilters: () -> Boolean,
    private val retainRowsDuringEdgePaging: () -> Boolean = { false },
) {
    private var currentData: HomeRenderData? = null
    private var lastRenderedData: HomeRenderData? = null
    private var currentCompanyLabelsByNumber: Map<String, List<HomeCompanyScopeLabel>> = emptyMap()
    private var currentCrmPhoneKeys: Set<String> = emptySet()

    fun invalidate() {
        currentData = null
    }

    fun showLoading() {
        prepareCustomersHeader()
        timelineToggle.prepare(visible = true, contactsMode = true)
        removeStatusRows()
        clearInlineStatus()
        val retainingRows = retainRowsDuringEdgePaging()
        if (!retainingRows) {
            currentData = null
            lastRenderedData = null
            currentCompanyLabelsByNumber = emptyMap()
            currentCrmPhoneKeys = emptySet()
            contentRenderer.clearCalls()
            addStatusRow(
                text = activity.getString(R.string.clients_status_loading),
                tagValue = SERVER_LOADING_STATUS_TAG,
            )
        }
        binding.fullLogProgress.visibility = View.GONE
        HomeLoadingFooterUi.show(binding.homeCallsContainer)
        binding.paginationContainer.visibility = View.GONE
    }

    fun render(
        data: HomeRenderData,
        pageSize: Int,
        refreshCompanyLabels: Boolean = true,
        totalItems: Int? = null,
        serverOffset: Int? = null,
        stale: Boolean = false,
    ) {
        prepareCustomersHeader()
        removeStatusRows()
        val previousData = lastRenderedData
        val previousLabels = currentCompanyLabelsByNumber
        val previousCrmKeys = currentCrmPhoneKeys
        val companyLabels = companyGeneralNotes.labelsFor(data.calls)
        val crmPhoneKeys = visibleCrmPhoneKeys(data.calls)
        currentData = data
        lastRenderedData = data
        currentCompanyLabelsByNumber = companyLabels
        currentCrmPhoneKeys = crmPhoneKeys
        contentRenderer.replaceCurrentCalls(data.calls)
        val page = HomePagedListUi.page(binding.homeCallsContainer, PageLoadingModeStore.usesPrefetch(activity), pageIndex())
        binding.fullLogProgress.visibility = View.GONE
        renderPagination(pageSize, data.calls.size, totalItems, serverOffset)
        val patched = reconcileRows(
            page, data, companyLabels, crmPhoneKeys,
            previousData, previousLabels, previousCrmKeys,
        )
        if (!patched) rebuildPage(page, data, companyLabels)
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
        if (stale) {
            showInlineStatus(activity.getString(R.string.clients_status_cached_refreshing), null)
        } else {
            clearInlineStatus()
        }
        if (refreshCompanyLabels) companyGeneralNotes.refresh(data.calls)
    }

    fun renderCurrentRowsAfterCompanyLabels(pageSize: Int) {
        val data = currentData ?: return
        render(data, pageSize, refreshCompanyLabels = false)
    }

    /** Temporary server failure is distinct from a valid empty response. */
    fun renderRefreshError(pageSize: Int, hasCachedRows: Boolean, onRetry: () -> Unit) {
        prepareCustomersHeader()
        removeStatusRows()
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
        binding.fullLogProgress.visibility = View.GONE
        val retryText = activity.getString(R.string.clients_status_refresh_failed_retry)
        if (!hasCachedRows) {
            currentData = null
            lastRenderedData = null
            currentCompanyLabelsByNumber = emptyMap()
            currentCrmPhoneKeys = emptySet()
            contentRenderer.clearCalls()
            addStatusRow(
                text = activity.getString(R.string.clients_status_load_failed),
                tagValue = ERROR_STATUS_TAG,
            )
            binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, pageSize)
            binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, pageSize)
            PaginationButtonAppearance.apply(binding.previousCallsButton, pageIndex() > 0)
            PaginationButtonAppearance.apply(binding.nextCallsButton, false)
            binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex() + 1)
            binding.paginationContainer.visibility = View.VISIBLE
        }
        showInlineStatus(retryText, onRetry)
    }

    /** Called only after an authoritative successful response with total=0. */
    fun renderEmpty(pageSize: Int) {
        prepareCustomersHeader()
        removeStatusRows()
        clearInlineStatus()
        if (retainRowsDuringEdgePaging() && pageIndex() > 0) {
            currentData = null
            lastRenderedData = null
            currentCompanyLabelsByNumber = emptyMap()
            currentCrmPhoneKeys = emptySet()
            contentRenderer.replaceCurrentCalls(emptyList())
            binding.fullLogProgress.visibility = View.GONE
            HomeLoadingFooterUi.hide(binding.homeCallsContainer)
            PaginationButtonAppearance.apply(binding.nextCallsButton, enabled = false)
            binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex() + 1)
            binding.paginationContainer.visibility = View.VISIBLE
            return
        }
        currentData = null
        lastRenderedData = null
        currentCompanyLabelsByNumber = emptyMap()
        currentCrmPhoneKeys = emptySet()
        contentRenderer.clearCalls()
        binding.fullLogProgress.visibility = View.GONE
        addStatusRow(
            text = activity.getString(
                if (hasActiveCrmFilters()) R.string.clients_status_empty_filtered else R.string.clients_status_empty,
            ),
            tagValue = EMPTY_STATUS_TAG,
        )
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
        timelineToggle.showEmpty(contactsMode = true)
        PaginationButtonAppearance.apply(binding.previousCallsButton, pageIndex() > 0)
        PaginationButtonAppearance.apply(binding.nextCallsButton, enabled = false)
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex() + 1)
        binding.paginationContainer.visibility = View.VISIBLE
        binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, pageSize)
        binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, pageSize)
    }

    private fun reconcileRows(
        page: LinearLayout,
        data: HomeRenderData,
        companyLabels: Map<String, List<HomeCompanyScopeLabel>>,
        crmPhoneKeys: Set<String>,
        previousData: HomeRenderData?,
        previousLabels: Map<String, List<HomeCompanyScopeLabel>>,
        previousCrmKeys: Set<String>,
    ): Boolean {
        val desiredTags = data.calls.map(::rowTag)
        if (desiredTags.any { it == CLIENT_ROW_TAG_PREFIX } || desiredTags.toSet().size != desiredTags.size) return false
        val existingTags = buildList {
            for (index in 0 until page.childCount) {
                val tag = page.getChildAt(index).tag as? String ?: return false
                if (!tag.startsWith(CLIENT_ROW_TAG_PREFIX)) return false
                add(tag)
            }
        }
        if (existingTags.toSet().size != existingTags.size) return false
        data.calls.forEachIndexed { targetIndex, contact ->
            val tag = rowTag(contact)
            val existingIndex = (0 until page.childCount).firstOrNull { index -> page.getChildAt(index).tag == tag } ?: -1
            val changed = rowChanged(contact, data, companyLabels, crmPhoneKeys, previousData, previousLabels, previousCrmKeys)
            if (existingIndex == targetIndex && !changed) return@forEachIndexed
            val view = if (existingIndex >= 0 && !changed) page.getChildAt(existingIndex) else buildRow(contact, data, companyLabels)
            if (existingIndex >= 0) page.removeViewAt(existingIndex)
            page.addView(view, targetIndex.coerceAtMost(page.childCount))
        }
        while (page.childCount > data.calls.size) page.removeViewAt(page.childCount - 1)
        return true
    }

    private fun rowChanged(
        contact: PhoneCallRecord,
        data: HomeRenderData,
        companyLabels: Map<String, List<HomeCompanyScopeLabel>>,
        crmPhoneKeys: Set<String>,
        previousData: HomeRenderData?,
        previousLabels: Map<String, List<HomeCompanyScopeLabel>>,
        previousCrmKeys: Set<String>,
    ): Boolean {
        previousData ?: return true
        val phoneKey = HomeCallPageLoader.noteKey(contact.number)
        val previousContact = previousData.calls.firstOrNull { HomeCallPageLoader.noteKey(it.number) == phoneKey } ?: return true
        if (previousContact != contact) return true
        val callKey = HomeCallNotesResolver.keyFor(contact)
        return displayName(data, contact) != displayName(previousData, previousContact) ||
            data.contactNotesByNumber[phoneKey] != previousData.contactNotesByNumber[phoneKey] ||
            data.callNotesByCall[callKey] != previousData.callNotesByCall[callKey] ||
            companyLabels[phoneKey] != previousLabels[phoneKey] ||
            (phoneKey in crmPhoneKeys) != (phoneKey in previousCrmKeys)
    }

    private fun rebuildPage(page: LinearLayout, data: HomeRenderData, companyLabels: Map<String, List<HomeCompanyScopeLabel>>) {
        page.removeAllViews()
        data.calls.forEach { contact -> page.addView(buildRow(contact, data, companyLabels)) }
    }

    private fun buildRow(contact: PhoneCallRecord, data: HomeRenderData, companyLabels: Map<String, List<HomeCompanyScopeLabel>>): View {
        val key = HomeCallPageLoader.noteKey(contact.number)
        val row = rowRenderer.compactRow(
            contact = contact,
            displayName = displayName(data, contact),
            contactNote = data.contactNotesByNumber[key],
            companyLabels = companyLabels[key],
            latestCallNote = data.callNotesByCall[HomeCallNotesResolver.keyFor(contact)],
            highlightQuery = "",
        )
        return ListThemeUi.applyRowSpacing(row, ::dp).apply { tag = rowTag(contact) }
    }

    private fun displayName(data: HomeRenderData, contact: PhoneCallRecord): String {
        val key = HomeCallPageLoader.noteKey(contact.number)
        return data.contactNamesByNumber[key].orEmpty().ifBlank { contact.displayName }
    }

    private fun visibleCrmPhoneKeys(calls: List<PhoneCallRecord>): Set<String> {
        val context = activity.applicationContext
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(context))) return emptySet()
        return calls.asSequence().filter { CrmContactSyncStore.isEnabled(context, it.number) }
            .map { HomeCallPageLoader.noteKey(it.number) }.filter(String::isNotBlank).toSet()
    }

    private fun rowTag(contact: PhoneCallRecord): String = CLIENT_ROW_TAG_PREFIX + HomeCallPageLoader.noteKey(contact.number)

    private fun renderPagination(pageSize: Int, itemCount: Int, totalItems: Int?, serverOffset: Int?) {
        timelineToggle.showRange(contactsMode = true, pageIndex = pageIndex(), pageSize = pageSize, itemCount = itemCount)
        binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, pageSize)
        binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, pageSize)
        val offset = serverOffset ?: pageIndex() * pageSize
        val hasNext = totalItems?.let { offset + itemCount < it } ?: (itemCount >= pageSize)
        PaginationButtonAppearance.apply(binding.previousCallsButton, offset > 0)
        PaginationButtonAppearance.apply(binding.nextCallsButton, hasNext)
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, offset / pageSize + 1)
        binding.paginationContainer.visibility = View.VISIBLE
    }

    private fun showInlineStatus(text: String, onClick: (() -> Unit)?) {
        binding.homeStatusText.text = text
        binding.homeStatusText.visibility = View.VISIBLE
        binding.homeStatusText.isClickable = onClick != null
        binding.homeStatusText.setOnClickListener(if (onClick == null) null else View.OnClickListener { onClick() })
    }

    private fun clearInlineStatus() {
        binding.homeStatusText.text = ""
        binding.homeStatusText.visibility = View.GONE
        binding.homeStatusText.isClickable = false
        binding.homeStatusText.setOnClickListener(null)
    }

    private fun addStatusRow(text: String, tagValue: String? = null) {
        binding.homeCallsContainer.addView(TextView(activity).apply {
            this.text = text
            tag = tagValue
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(dp(18), dp(28), dp(18), dp(28))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun removeStatusRows() {
        for (index in binding.homeCallsContainer.childCount - 1 downTo 0) {
            when (binding.homeCallsContainer.getChildAt(index).tag) {
                SERVER_LOADING_STATUS_TAG, EMPTY_STATUS_TAG, ERROR_STATUS_TAG -> binding.homeCallsContainer.removeViewAt(index)
            }
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun prepareCustomersHeader() {
        binding.crmControlsScroll.visibility = View.GONE
        binding.crmContactsTitleText.text = activity.getString(R.string.runtime_crm_clients)
    }

    private companion object {
        const val SERVER_LOADING_STATUS_TAG = "relationship_manager_clients_server_loading"
        const val EMPTY_STATUS_TAG = "relationship_manager_clients_empty"
        const val ERROR_STATUS_TAG = "relationship_manager_clients_error"
        const val CLIENT_ROW_TAG_PREFIX = "relationship_manager_client_row:"
    }
}
