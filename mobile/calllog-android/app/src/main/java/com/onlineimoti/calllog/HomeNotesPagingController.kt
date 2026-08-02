package com.onlineimoti.calllog

import android.app.Activity
import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal class HomeEdgePagingController(
    private val binding: ActivityHomeBinding,
    canPrevious: () -> Boolean,
    canNext: () -> Boolean,
    previousPage: () -> Unit,
    nextPage: () -> Unit,
) {
    private val activity = binding.root.context as? Activity
    private val originalPaginationHeight = binding.paginationContainer.layoutParams.height
    private val stickyGroups = StickyGroupHeaderController(
        binding.homeCallsScrollView,
        binding.homeCallsContainer,
        binding.stickyGroupHeader,
    )
    private var pagingBusyToken = 0L
    private val paginationLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        hideAutomaticModeNavigation()
    }
    private val delegate = EdgePageScrollController(
        canPrevious = canPrevious,
        canNext = { PageLoadingModeStore.usesPrefetch(binding.root.context) && canNext() },
        previousPage = previousPage,
        nextPage = nextPage,
        pageReady = {
            val prefetch = PageLoadingModeStore.usesPrefetch(binding.root.context)
            HomePageReadyState.isReady() &&
                binding.fullLogProgress.visibility != View.VISIBLE &&
                (prefetch || binding.paginationContainer.visibility == View.VISIBLE)
        },
        retainPreviousPages = true,
        protectRetainedPrefix = true,
        // Never fetch a new page automatically. The user must reach the actual bottom.
        prefetchNext = false,
        loadAtBottomOnly = true,
        // Keep the spinner visible briefly before starting the next page request.
        loadingIndicatorLeadMs = 180L,
        onLoadingChanged = { loading ->
            binding.fullLogProgress.visibility = View.GONE
            if (loading) {
                beginPagingStatus()
                HomeLoadingFooterUi.show(binding.homeCallsContainer)
                binding.homeCallsScrollView.post {
                    binding.homeCallsScrollView.fullScroll(View.FOCUS_DOWN)
                }
            } else {
                finishPagingStatus()
                HomeLoadingFooterUi.hide(binding.homeCallsContainer)
            }
        },
        // Supplemental note/name renders may briefly rebuild the current page while
        // the next page is still loading. Keep the bottom spinner visible until the
        // edge controller confirms that the new page is stable.
        onLoadingStillPending = {
            HomeLoadingFooterUi.show(binding.homeCallsContainer)
        },
    )

    init {
        activity?.let(HomePageReadyState::attach)
        binding.paginationContainer.addOnLayoutChangeListener(paginationLayoutListener)
        HomePageReadyState.setOnReady { bind() }
        updateNavigationVisibility()
        stickyGroups.bind()
    }

    fun bind() {
        updateNavigationVisibility()
        delegate.bind(binding.homeCallsScrollView, binding.homeCallsContainer)
        stickyGroups.bind()
    }

    fun cancel() {
        delegate.cancelPending()
        finishPagingStatus()
    }

    fun isTransitioning(): Boolean = delegate.isTransitioning()

    fun release() {
        binding.paginationContainer.removeOnLayoutChangeListener(paginationLayoutListener)
        HomePageReadyState.clearOnReady()
        activity?.let(HomePageReadyState::detach)
        delegate.release()
        finishPagingStatus()
        stickyGroups.release()
    }

    private fun beginPagingStatus() {
        finishPagingStatus()
        pagingBusyToken = activity?.let {
            HomeBusyTooltipUi.begin(it, HomeBusyWork.MORE_CALLS)
        } ?: 0L
    }

    private fun finishPagingStatus() {
        val token = pagingBusyToken
        pagingBusyToken = 0L
        activity?.let { HomeBusyTooltipUi.end(it, token) }
    }

    private fun updateNavigationVisibility() {
        if (PageLoadingModeStore.usesPrefetch(binding.root.context)) {
            collapseAutomaticModeNavigation()
        } else {
            restoreManualModeNavigationHeight()
            if (
                HomePageReadyState.isReady() &&
                binding.fullLogProgress.visibility != View.VISIBLE
            ) {
                binding.paginationContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun hideAutomaticModeNavigation() {
        if (PageLoadingModeStore.usesPrefetch(binding.root.context)) {
            collapseAutomaticModeNavigation()
        }
    }

    private fun collapseAutomaticModeNavigation() {
        val params = binding.paginationContainer.layoutParams
        if (params.height != 0) {
            params.height = 0
            binding.paginationContainer.layoutParams = params
        }
        binding.paginationContainer.visibility = View.GONE
    }

    private fun restoreManualModeNavigationHeight() {
        val params = binding.paginationContainer.layoutParams
        if (params.height != originalPaginationHeight) {
            params.height = originalPaginationHeight
            binding.paginationContainer.layoutParams = params
        }
    }
}
