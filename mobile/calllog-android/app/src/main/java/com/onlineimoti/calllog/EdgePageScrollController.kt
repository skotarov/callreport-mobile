package com.onlineimoti.calllog

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView

/** Loads older pages without moving existing rows or changing the current viewport. */
internal class EdgePageScrollController(
    @Suppress("UNUSED_PARAMETER") canPrevious: () -> Boolean,
    private val canNext: () -> Boolean,
    @Suppress("UNUSED_PARAMETER") previousPage: () -> Unit,
    private val nextPage: () -> Unit,
    private val pageReady: () -> Boolean,
    @Suppress("UNUSED_PARAMETER") retainPreviousPages: Boolean = true,
    @Suppress("UNUSED_PARAMETER") protectRetainedPrefix: Boolean = false,
    private val prefetchNext: Boolean = true,
    private val loadAtBottomOnly: Boolean = false,
    private val loadingIndicatorLeadMs: Long = 0L,
    private val onLoadingChanged: (Boolean) -> Unit = {},
    private val onLoadingStillPending: () -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private var scrollView: ScrollView? = null
    private var content: LinearLayout? = null
    private var pendingNext = false
    private var initialPrefetchDone = false
    private var initialChecks = 0
    private var readyChecks = 0
    private var stableChecks = 0
    private var lastVisiblePageCount = -1
    private var baselineVisiblePageCount = 0
    private var lastScrollY = 0

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        val scroll = scrollView ?: return@OnScrollChangedListener
        val scrollY = scroll.scrollY
        val oldScrollY = lastScrollY
        lastScrollY = scrollY
        if (scrollY <= oldScrollY || pendingNext) return@OnScrollChangedListener
        val child = scroll.getChildAt(0) ?: return@OnScrollChangedListener
        val remaining = child.height - scrollY - scroll.height
        val threshold = if (loadAtBottomOnly) 0 else maxOf(scroll.height, MIN_PREFETCH_DISTANCE_PX)
        if (remaining <= threshold) requestNext()
    }

    private val initialPrefetch = object : Runnable {
        override fun run() {
            if (initialPrefetchDone || pendingNext || !prefetchNext) return
            val scroll = scrollView
            val list = content
            val ready = scroll != null && list != null && scroll.isAttachedToWindow &&
                HomePagedListUi.visiblePageCount(list) > 0 && pageReady()
            if (!ready) {
                if (++initialChecks < MAX_INITIAL_CHECKS) {
                    handler.postDelayed(this, READY_CHECK_DELAY_MS)
                }
                return
            }
            initialChecks = 0
            if (canNext()) requestNext() else initialPrefetchDone = true
        }
    }

    private val readyCheck = object : Runnable {
        override fun run() {
            if (!pendingNext) return
            onLoadingStillPending()
            val scroll = scrollView
            val list = content
            val visiblePageCount = list?.let(HomePagedListUi::visiblePageCount) ?: 0
            val freshPageVisible = scroll != null && list != null && scroll.isAttachedToWindow &&
                pageReady() && (visiblePageCount > baselineVisiblePageCount || !canNext())
            if (!freshPageVisible) {
                if (++readyChecks < MAX_READY_CHECKS) {
                    handler.postDelayed(this, READY_CHECK_DELAY_MS)
                } else {
                    abortPending()
                }
                return
            }
            stableChecks = if (visiblePageCount == lastVisiblePageCount) stableChecks + 1 else 0
            lastVisiblePageCount = visiblePageCount
            if (stableChecks < REQUIRED_STABLE_CHECKS) {
                handler.postDelayed(this, READY_CHECK_DELAY_MS)
                return
            }
            completePending()
        }
    }

    fun bind(
        scrollView: ScrollView,
        contentView: View,
        @Suppress("UNUSED_PARAMETER") nextPageTop: () -> Int = { 0 },
    ) {
        val list = contentView as? LinearLayout ?: return
        if (this.scrollView !== scrollView) {
            detachScrollListener()
            this.scrollView = scrollView
            lastScrollY = scrollView.scrollY
            scrollView.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        }
        content = list
        if (pendingNext) scheduleReadyCheck() else scheduleInitialPrefetch()
    }

    fun isTransitioning(): Boolean = pendingNext

    fun cancelPending() {
        handler.removeCallbacksAndMessages(null)
        val wasPending = pendingNext
        pendingNext = false
        initialPrefetchDone = false
        initialChecks = 0
        readyChecks = 0
        stableChecks = 0
        lastVisiblePageCount = -1
        baselineVisiblePageCount = 0
        if (wasPending) onLoadingChanged(false)
    }

    fun release() {
        cancelPending()
        detachScrollListener()
        scrollView = null
        content = null
    }

    private fun detachScrollListener() {
        val observer = scrollView?.viewTreeObserver ?: return
        if (observer.isAlive) observer.removeOnScrollChangedListener(scrollListener)
    }

    private fun requestNext() {
        if (pendingNext || !canNext()) return
        val list = content ?: return
        baselineVisiblePageCount = HomePagedListUi.visiblePageCount(list)
        pendingNext = true
        onLoadingChanged(true)
        readyChecks = 0
        stableChecks = 0
        lastVisiblePageCount = -1
        if (loadingIndicatorLeadMs > 0L) {
            handler.postDelayed(::startNextPage, loadingIndicatorLeadMs)
        } else {
            startNextPage()
        }
    }

    private fun startNextPage() {
        if (!pendingNext) return
        runCatching(nextPage).onFailure { abortPending() }
        if (pendingNext) scheduleReadyCheck()
    }

    private fun completePending() {
        pendingNext = false
        initialPrefetchDone = true
        readyChecks = 0
        stableChecks = 0
        lastVisiblePageCount = -1
        onLoadingChanged(false)
    }

    private fun scheduleInitialPrefetch() {
        if (!prefetchNext || initialPrefetchDone || pendingNext) return
        handler.removeCallbacks(initialPrefetch)
        handler.postDelayed(initialPrefetch, INITIAL_PREFETCH_DELAY_MS)
    }

    private fun scheduleReadyCheck() {
        handler.removeCallbacks(readyCheck)
        handler.post(readyCheck)
    }

    private fun abortPending() {
        handler.removeCallbacks(readyCheck)
        val wasPending = pendingNext
        pendingNext = false
        readyChecks = 0
        stableChecks = 0
        lastVisiblePageCount = -1
        if (wasPending) onLoadingChanged(false)
    }

    private companion object {
        const val READY_CHECK_DELAY_MS = 60L
        const val INITIAL_PREFETCH_DELAY_MS = 1_500L
        const val MAX_READY_CHECKS = 160
        const val MAX_INITIAL_CHECKS = 160
        const val REQUIRED_STABLE_CHECKS = 2
        const val MIN_PREFETCH_DISTANCE_PX = 600
    }
}
