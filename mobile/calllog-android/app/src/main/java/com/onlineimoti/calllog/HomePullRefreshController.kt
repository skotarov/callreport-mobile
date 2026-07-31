package com.onlineimoti.calllog

import android.os.Handler
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Owns Home's refresh spinner and protects it from stuck async reloads. */
internal class HomePullRefreshController(
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
) {
    private var inProgress = false
    private val timeoutWatcher = Runnable { complete() }

    fun bind(onRefresh: () -> Unit) {
        binding.homeCallsRefreshLayout.setOnRefreshListener { request(onRefresh) }
    }

    fun request(refresh: () -> Unit) {
        if (inProgress) complete()
        inProgress = true
        if (HomePagedListUi.visiblePageCount(binding.homeCallsContainer) == 0) {
            // The in-memory model can survive while Android has lost the rendered
            // page. Force one rebuild so equal freshly-read data cannot leave it white.
            HomeRefreshRenderPolicy.requestForceRebuild()
        }
        handler.removeCallbacks(timeoutWatcher)
        handler.postDelayed(timeoutWatcher, REFRESH_TIMEOUT_MS)
        runCatching { refresh() }.onFailure { complete() }
    }

    fun isInProgress(): Boolean = inProgress

    fun complete() {
        if (!inProgress) {
            binding.homeCallsRefreshLayout.setRefreshing(false)
            return
        }
        inProgress = false
        handler.removeCallbacks(timeoutWatcher)
        binding.homeCallsRefreshLayout.setRefreshing(false)
    }

    fun cancel() {
        inProgress = false
        handler.removeCallbacks(timeoutWatcher)
        binding.homeCallsRefreshLayout.setRefreshing(false)
    }

    private companion object {
        const val REFRESH_TIMEOUT_MS = 8_000L
    }
}
