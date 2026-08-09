package com.onlineimoti.calllog

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Draws the server-backed Clients list while retaining Home's existing paging controls. */
internal class HomeCrmContactsContentView(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val pageIndex: () -> Int,
    @Suppress("UNUSED_PARAMETER") contentRenderer: HomeContentRenderer,
    private val companyGeneralNotes: HomeCompanyGeneralNotesController,
    private val rowRenderer: HomeCrmContactRowRenderer,
    private val timelineToggle: HomeCrmTimelineModeToggle,
    private val hasActiveCrmFilters: () -> Boolean,
    private val retainRowsDuringEdgePaging: () -> Boolean = { false },
) {
    private var currentClients: List<ServerCrmClient>? = null
    private var lastRenderedClients: List<ServerCrmClient>? = null
    private var currentCompanyLabelsByNumber: Map<String, List<HomeCompanyScopeLabel>> = emptyMap()
    private var currentTotalItems: Int? = null
    private var currentServerOffset: Int? = null
    private var currentStale: Boolean = false

    fun invalidate() {
        currentClients = null
    }

    fun showLoading() {
        prepareCustomersHeader()
        timelineToggle.prepare(visible = true, contactsMode = true)
        removeStatusRows()
        clearInlineStatus()
        val retainingRows = retainRowsDuringEdgePaging()
        if (!retainingRows) {
            resetRenderedState()
            HomePagedListUi.clear(binding.homeCallsContainer)
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
        clients: List<ServerCrmClient>,
        pageSize: Int,
        refreshCompanyLabels: Boolean = true,
        totalItems: Int? = null,
        serverOffset: Int? = null,
        stale: Boolean = false,
    ) {
        prepareCustomersHeader()
        removeStatusRows()
        val previousClients = lastRenderedClients
        val previousLabels = currentCompanyLabelsByNumber
        val companyLabels = companyGeneralNotes.labelsForPhones(clients.map { it.phone })
        currentClients = clients
        lastRenderedClients = clients
        currentCompanyLabelsByNumber = companyLabels
        currentTotalItems = totalItems
        currentServerOffset = serverOffset
        currentStale = stale
        val page = HomePagedListUi.page(binding.homeCallsContainer, PageLoadingModeStore.usesPrefetch(activity), pageIndex())
        binding.fullLogProgress.visibility = View.GONE
        renderPagination(pageSize, clients.size, totalItems, serverOffset)
        val patched = reconcileRows(page, clients, companyLabels, previousClients, previousLabels)
        if (!patched) rebuildPage(page, clients, companyLabels)
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
        if (stale) {
            showInlineStatus(activity.getString(R.string.clients_status_cached_refreshing), null)
        } else {
            clearInlineStatus()
        }
        if (refreshCompanyLabels) companyGeneralNotes.refreshPhones(clients.map { it.phone })
    }

    fun renderCurrentRowsAfterCompanyLabels(pageSize: Int) {
        val clients = currentClients ?: return
        render(
            clients = clients,
            pageSize = pageSize,
            refreshCompanyLabels = false,
            totalItems = currentTotalItems,
            serverOffset = currentServerOffset,
            stale = currentStale,
        )
    }

    /** Temporary server failure is distinct from a valid empty response. */
    fun renderRefreshError(pageSize: Int, hasCachedRows: Boolean, onRetry: () -> Unit) {
        prepareCustomersHeader()
        removeStatusRows()
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
        binding.fullLogProgress.visibility = View.GONE
        val retryText = activity.getString(R.string.clients_status_refresh_failed_retry)
        if (!hasCachedRows) {
            resetRenderedState()
            HomePagedListUi.clear(binding.homeCallsContainer)
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
            resetRenderedState()
            binding.fullLogProgress.visibility = View.GONE
            HomeLoadingFooterUi.hide(binding.homeCallsContainer)
            PaginationButtonAppearance.apply(binding.nextCallsButton, enabled = false)
            binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex() + 1)
            binding.paginationContainer.visibility = View.VISIBLE
            return
        }
        resetRenderedState()
        HomePagedListUi.clear(binding.homeCallsContainer)
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
        clients: List<ServerCrmClient>,
        companyLabels: Map<String, List<HomeCompanyScopeLabel>>,
        previousClients: List<ServerCrmClient>?,
        previousLabels: Map<String, List<HomeCompanyScopeLabel>>,
    ): Boolean {
        val desiredTags = clients.map(::rowTag)
        if (desiredTags.any { it == CLIENT_ROW_TAG_PREFIX } || desiredTags.toSet().size != desiredTags.size) return false
        val existingTags = buildList {
            for (index in 0 until page.childCount) {
                val tag = page.getChildAt(index).tag as? String ?: return false
                if (!tag.startsWith(CLIENT_ROW_TAG_PREFIX)) return false
                add(tag)
            }
        }
        if (existingTags.toSet().size != existingTags.size) return false
        clients.forEachIndexed { targetIndex, client ->
            val tag = rowTag(client)
            val existingIndex = (0 until page.childCount).firstOrNull { index -> page.getChildAt(index).tag == tag } ?: -1
            val changed = rowChanged(client, companyLabels, previousClients, previousLabels)
            if (existingIndex == targetIndex && !changed) return@forEachIndexed
            val view = if (existingIndex >= 0 && !changed) page.getChildAt(existingIndex) else buildRow(client, companyLabels)
            if (existingIndex >= 0) page.removeViewAt(existingIndex)
            page.addView(view, targetIndex.coerceAtMost(page.childCount))
        }
        while (page.childCount > clients.size) page.removeViewAt(page.childCount - 1)
        return true
    }

    private fun rowChanged(
        client: ServerCrmClient,
        companyLabels: Map<String, List<HomeCompanyScopeLabel>>,
        previousClients: List<ServerCrmClient>?,
        previousLabels: Map<String, List<HomeCompanyScopeLabel>>,
    ): Boolean {
        previousClients ?: return true
        val tag = rowTag(client)
        val previousClient = previousClients.firstOrNull { rowTag(it) == tag } ?: return true
        if (previousClient != client) return true
        val phoneKey = HomeCallPageLoader.noteKey(client.phone)
        return companyLabels[phoneKey] != previousLabels[phoneKey]
    }

    private fun rebuildPage(
        page: LinearLayout,
        clients: List<ServerCrmClient>,
        companyLabels: Map<String, List<HomeCompanyScopeLabel>>,
    ) {
        page.removeAllViews()
        clients.forEach { client -> page.addView(buildRow(client, companyLabels)) }
    }

    private fun buildRow(
        client: ServerCrmClient,
        companyLabels: Map<String, List<HomeCompanyScopeLabel>>,
    ): View {
        val key = HomeCallPageLoader.noteKey(client.phone)
        val row = rowRenderer.compactRow(
            client = client,
            displayName = client.name.ifBlank { client.phone },
            contactNote = latestNoteLabel(client),
            companyLabels = companyLabels[key],
            highlightQuery = "",
        )
        return ListThemeUi.applyRowSpacing(row, ::dp).apply { tag = rowTag(client) }
    }

    private fun latestNoteLabel(client: ServerCrmClient): String? {
        val latest = client.notes.maxByOrNull { note -> maxOf(note.updatedAtMs, note.createdAtMs) } ?: return null
        return if (latest.authorName.isBlank()) latest.text else "${latest.authorName}: ${latest.text}"
    }

    private fun rowTag(client: ServerCrmClient): String {
        val key = client.identity.trim().ifBlank {
            client.normalizedPhone.ifBlank { HomeCallPageLoader.noteKey(client.phone) }
        }
        return CLIENT_ROW_TAG_PREFIX + key
    }

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

    private fun resetRenderedState() {
        currentClients = null
        lastRenderedClients = null
        currentCompanyLabelsByNumber = emptyMap()
        currentTotalItems = null
        currentServerOffset = null
        currentStale = false
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
