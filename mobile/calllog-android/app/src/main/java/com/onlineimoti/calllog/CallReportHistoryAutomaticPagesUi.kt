package com.onlineimoti.calllog

import android.app.Activity
import android.widget.LinearLayout

/** Appends automatic Notes/SMS pages to the live History root without replacing its ScrollView. */
internal class CallReportHistoryAutomaticPagesUi(
    private val activity: Activity,
) {
    private var binding: Binding? = null

    fun bind(
        root: LinearLayout,
        pages: List<List<CallReportHistoryRow>>,
        currentPageIndex: Int,
        renderPage: (LinearLayout, List<CallReportHistoryRow>, CallReportHistoryRow?) -> Unit,
    ) {
        binding = Binding(root, pages, renderPage)
        for (index in 0..currentPageIndex) appendPage(binding ?: return, index, requireAttached = false)
    }

    fun appendCurrentPage(pageIndex: Int): Boolean {
        val current = binding ?: return false
        return appendPage(current, pageIndex, requireAttached = true)
    }

    fun reset() {
        binding = null
    }

    private fun appendPage(current: Binding, index: Int, requireAttached: Boolean): Boolean {
        if (requireAttached && !current.root.isAttachedToWindow) return false
        val rows = current.pages.getOrNull(index) ?: return false
        if (HomePagedListUi.hasRenderedPage(current.root, index)) {
            HomeLoadingFooterUi.keepLast(current.root)
            return true
        }
        val previousRow = current.pages.take(index).asReversed().firstNotNullOfOrNull { it.lastOrNull() }
        val pageRoot = HomePagedListUi.page(current.root, automatic = true, pageIndex = index)
        current.renderPage(pageRoot, rows, previousRow)
        CrmHistoryTextLocalizer.apply(activity, pageRoot)
        HomeLoadingFooterUi.keepLast(current.root)
        current.root.requestLayout()
        return true
    }

    private data class Binding(
        val root: LinearLayout,
        val pages: List<List<CallReportHistoryRow>>,
        val renderPage: (LinearLayout, List<CallReportHistoryRow>, CallReportHistoryRow?) -> Unit,
    )
}
