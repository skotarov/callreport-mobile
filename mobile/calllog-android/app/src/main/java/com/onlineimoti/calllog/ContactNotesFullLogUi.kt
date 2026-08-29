package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Renders the shared filtered full-log entries inside the existing History screen. */
internal class ContactNotesFullLogUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    private val weekUi by lazy { CallReportHistoryWeekUi(activity, dp) }
    private var entries: List<FilteredFullLogEntry> = emptyList()
    private var pageIndex = 0
    private var boundSection: BoundSection? = null

    fun addSection(
        root: LinearLayout,
        phone: String,
        incomingEntries: List<FilteredFullLogEntry>,
        remoteEnabled: Boolean,
        loading: Boolean,
        errorText: String,
        openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    ) {
        entries = incomingEntries
        root.tag = FULL_LOG_ROOT_TAG
        // Everything before this index is the fixed contact/header part of History.
        val section = BoundSection(
            root = root,
            sectionStartIndex = root.childCount,
            phone = phone,
            remoteEnabled = remoteEnabled,
            loading = loading,
            errorText = errorText,
            openCallNoteEditor = openCallNoteEditor,
        )
        boundSection = section
        renderSection(section)
    }

    fun canPreviousPage(): Boolean = pageIndex > 0

    fun canNextPage(): Boolean = pageIndex < maxOf(0, pages().size - 1)

    fun previousPage(rerender: () -> Unit): Boolean {
        if (!canPreviousPage()) return false
        pageIndex -= 1
        rerender()
        return true
    }

    fun nextPage(rerender: () -> Unit): Boolean {
        if (!canNextPage()) return false
        val nextIndex = pageIndex + 1
        val section = boundSection
        if (
            PageLoadingModeStore.usesPrefetch(activity) &&
            section != null && section.root.isAttachedToWindow
        ) {
            pageIndex = nextIndex
            if (appendAutomaticPage(section, nextIndex)) return true
            pageIndex -= 1
            return false
        }
        pageIndex = nextIndex
        rerender()
        return true
    }

    fun resetPage() {
        pageIndex = 0
        boundSection = null
    }

    private fun renderSection(section: BoundSection) {
        val root = section.root
        while (root.childCount > section.sectionStartIndex) root.removeViewAt(root.childCount - 1)
        val pages = pages()
        val totalPages = maxOf(1, pages.size)
        pageIndex = pageIndex.coerceIn(0, totalPages - 1)
        when {
            section.loading && entries.isEmpty() -> root.addView(status("Зареждам пълния лог…"))
            section.errorText.isNotBlank() && entries.isEmpty() -> root.addView(status(section.errorText, error = true))
            entries.isEmpty() -> root.addView(status(
                if (section.remoteEnabled) "Няма локални или сървърни записи за този номер"
                else "Няма локални записи за този номер",
            ))
            PageLoadingModeStore.usesPrefetch(activity) -> renderAutomaticPages(section, pages)
            else -> {
                val visibleEntries = pages.take(pageIndex + 1).flatten()
                renderEntries(
                    root = root,
                    phone = section.phone,
                    pageEntries = visibleEntries,
                    remoteEnabled = section.remoteEnabled,
                    openCallNoteEditor = section.openCallNoteEditor,
                )
                addNavigation(
                    root = root,
                    totalPages = totalPages,
                    onPrevious = {
                        if (canPreviousPage()) {
                            pageIndex -= 1
                            renderSection(section)
                        }
                    },
                    onNext = {
                        if (canNextPage()) {
                            pageIndex += 1
                            renderSection(section)
                        }
                    },
                )
            }
        }
    }

    private fun renderAutomaticPages(
        section: BoundSection,
        pages: List<List<FilteredFullLogEntry>>,
    ) {
        var previousWeekSerial: Long? = null
        for (index in 0..pageIndex) {
            val pageEntries = pages.getOrNull(index).orEmpty()
            val pageRoot = HomePagedListUi.page(section.root, automatic = true, pageIndex = index)
            previousWeekSerial = renderEntries(
                root = pageRoot,
                phone = section.phone,
                pageEntries = pageEntries,
                remoteEnabled = section.remoteEnabled,
                openCallNoteEditor = section.openCallNoteEditor,
                initialPreviousWeekSerial = previousWeekSerial,
            )
        }
    }

    private fun appendAutomaticPage(section: BoundSection, index: Int): Boolean {
        val allPages = pages()
        val pageEntries = allPages.getOrNull(index) ?: return false
        if (HomePagedListUi.hasRenderedPage(section.root, index)) {
            HomeLoadingFooterUi.keepLast(section.root)
            return true
        }
        val previousEntry = allPages.take(index).asReversed().firstNotNullOfOrNull { it.lastOrNull() }
        val previousWeekSerial = previousEntry?.let { weekUi.weekStartSerial(it.row.timeMs) }
        val pageRoot = HomePagedListUi.page(section.root, automatic = true, pageIndex = index)
        renderEntries(
            root = pageRoot,
            phone = section.phone,
            pageEntries = pageEntries,
            remoteEnabled = section.remoteEnabled,
            openCallNoteEditor = section.openCallNoteEditor,
            initialPreviousWeekSerial = previousWeekSerial,
        )
        CrmHistoryTextLocalizer.apply(activity, pageRoot)
        HomeLoadingFooterUi.keepLast(section.root)
        section.root.requestLayout()
        return true
    }

    private fun renderEntries(
        root: LinearLayout,
        phone: String,
        pageEntries: List<FilteredFullLogEntry>,
        remoteEnabled: Boolean,
        openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
        initialPreviousWeekSerial: Long? = null,
    ): Long? {
        val rowRenderer = FilteredFullLogRowRenderer(
            activity = activity,
            dp = dp,
            roundedRect = roundedRect,
            openContactNotes = null,
            openCallNoteEditor = openCallNoteEditor,
        )
        val currentWeekSerial = weekUi.currentWeekSerial()
        var previousWeekSerial = initialPreviousWeekSerial
        pageEntries.forEach { entry ->
            val weekSerial = weekUi.weekStartSerial(entry.row.timeMs)
            if (weekSerial != null && weekSerial != previousWeekSerial) {
                val relativeWeeks = currentWeekSerial
                    ?.let { (it - weekSerial) / CallReportHistoryWeekUi.DAYS_PER_WEEK }
                    ?: 0L
                root.addView(weekUi.separator(entry.row.timeMs, relativeWeeks))
                previousWeekSerial = weekSerial
            }
            root.addView(ListThemeUi.applyRowSpacing(
                rowRenderer.rowView(phone, entry, remoteEnabled),
                dp,
            ))
        }
        return previousWeekSerial
    }

    private fun addNavigation(
        root: LinearLayout,
        totalPages: Int,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
    ) {
        if (totalPages <= 1 || PageLoadingModeStore.usesPrefetch(activity)) return
        val pageSize = ConfigStore.load(activity).homeCallPageSize.coerceIn(5, 100)
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(pageButton(
                label = activity.getString(R.string.history_page_previous, pageSize),
                enabled = canPreviousPage(),
                action = onPrevious,
            ), LinearLayout.LayoutParams(0, dp(38), 1f))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.history_page_status, pageIndex + 1, totalPages)
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(100, 116, 139))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
            addView(pageButton(
                label = activity.getString(R.string.history_page_next, pageSize),
                enabled = canNextPage(),
                action = onNext,
            ), LinearLayout.LayoutParams(0, dp(38), 1f))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
    }

    private fun pageButton(label: String, enabled: Boolean, action: () -> Unit): TextView {
        val textColor = if (enabled) Color.rgb(55, 65, 81) else Color.rgb(156, 163, 175)
        val backgroundColor = if (enabled) Color.rgb(248, 250, 252) else Color.rgb(249, 250, 251)
        return TextView(activity).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(textColor)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            setPadding(dp(6), 0, dp(6), 0)
            background = roundedRect(backgroundColor, dp(10), Color.rgb(226, 232, 240), dp(1))
            if (enabled) setOnClickListener { action() }
        }
    }

    private fun pages(): List<List<FilteredFullLogEntry>> = TimelinePageMode.pages(
        context = activity,
        items = entries,
        pageSize = ConfigStore.load(activity).homeCallPageSize.coerceIn(5, 100),
        groupKey = { entry -> TimelineGroupKeys.week(entry.row.timeMs) },
    )

    private fun status(textValue: String, error: Boolean = false): TextView = TextView(activity).apply {
        text = textValue
        textSize = 13.5f
        if (error) setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (error) Color.rgb(185, 28, 28) else Color.rgb(100, 116, 139))
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(24), dp(12), dp(24))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private data class BoundSection(
        val root: LinearLayout,
        val sectionStartIndex: Int,
        val phone: String,
        val remoteEnabled: Boolean,
        val loading: Boolean,
        val errorText: String,
        val openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    )

    companion object {
        const val FULL_LOG_ROOT_TAG = "relationship_manager_history_full_log_root"
    }
}
