package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Replaces only rows whose visible note/name state changed, preserving the list and scroll position. */
internal fun HomeContentRenderer.patchChangedRows(
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

internal fun HomeContentRenderer.rebuildPage(
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

internal fun HomeContentRenderer.buildRow(
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

internal fun HomeContentRenderer.rowTag(call: PhoneCallRecord): String =
    "$HOME_ROW_TAG_PREFIX${HomeCallNotesResolver.keyFor(call)}"

internal fun HomeContentRenderer.dateSeparator(timestamp: Long, relativeDays: Long, hasRowsBefore: Boolean): TextView {
    val locale = if (AppLocaleText.isBulgarian()) Locale("bg", "BG") else Locale.US
    val label = "${SimpleDateFormat("EEEE, d MMMM yyyy", locale).format(Date(timestamp))} " +
        "(${HomeTimelineDateUi.relativeDaysLabel(activity, relativeDays)})"
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

internal fun HomeContentRenderer.renderStatusAndPagination(pageSize: Int) {
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

internal fun HomeContentRenderer.updateStatusStyle(hidePlainTimelineRange: Boolean = false) {
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

internal fun HomeContentRenderer.showResultsStatus(text: String) {
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

internal fun HomeContentRenderer.isTopLevelCrmPage(): Boolean = isCrmModeEnabled() || isCrmContactsMode()

internal fun HomeContentRenderer.updateCrmModeControls() {
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

private const val HOME_ROW_TAG_PREFIX = "relationship_manager_home_row:"
