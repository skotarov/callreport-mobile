package com.onlineimoti.calllog

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/** Header indicator for durable local changes while a contact History screen is open. */
internal class HistorySyncStatusUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var indicator: IndicatorViews? = null
    private val refreshRunnable = Runnable { refresh() }

    fun create(): View {
        val size = dp(36)
        val container = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val badge = TextView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(17),
                Gravity.TOP or Gravity.END,
            )
            minWidth = dp(17)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(4), 0, dp(4), 0)
            setTextColor(Color.WHITE)
            textSize = 9f
        }
        container.addView(icon)
        container.addView(badge)
        indicator = IndicatorViews(container, icon, badge)
        refresh()
        return container
    }

    fun release() {
        handler.removeCallbacks(refreshRunnable)
        indicator = null
    }

    private fun refresh() {
        if (activity.isFinishing || activity.isDestroyed) return
        val views = indicator ?: return
        val summary = PendingSyncStatus.summary(activity.applicationContext)
        val state = homeSyncStatusState(summary.count, summary.failure.isNotBlank())
        views.container.visibility = if (state.visible) View.VISIBLE else View.GONE
        if (!state.visible) {
            views.badge.text = ""
            handler.removeCallbacks(refreshRunnable)
            return
        }
        views.icon.setImageResource(
            if (state.hasIssue) R.drawable.ic_cloud_sync_issue else R.drawable.ic_cloud_sync_pending,
        )
        views.badge.text = state.badgeText
        views.badge.background = badgeBackground(state.hasIssue)
        views.container.contentDescription = activity.getString(
            if (state.hasIssue) R.string.home_sync_issue_content_description else R.string.home_sync_pending_content_description,
            state.badgeText,
        )
        views.container.setOnClickListener { showDetails(summary) }
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, RECHECK_DELAY_MS)
    }

    private fun showDetails(summary: PendingSyncSummary) {
        if (summary.count <= 0 || activity.isFinishing || activity.isDestroyed) return
        val message = if (summary.failure.isBlank()) {
            activity.getString(R.string.home_sync_pending_message, summary.count)
        } else {
            activity.getString(R.string.home_sync_issue_message, summary.count, summary.failure)
        }
        AlertDialog.Builder(activity)
            .setTitle(if (summary.failure.isBlank()) R.string.home_sync_pending_title else R.string.home_sync_issue_title)
            .setMessage(message)
            .setNegativeButton(R.string.home_sync_close, null)
            .setPositiveButton(R.string.home_sync_retry) { _, _ -> retryPendingSync() }
            .show()
    }

    private fun retryPendingSync() {
        val appContext = activity.applicationContext
        CallReportNoteOutboxScheduler.enqueue(appContext, reason = "history_sync_indicator_retry")
        CallReportTopicNoteOutbox.requestSyncNow(appContext)
        CompanyCallNoteOutbox.requestSyncNow(appContext)
        AccountMutationOutbox.schedulePending(appContext, replace = true)
        CallReportSyncScheduler.enqueueCatchUp(appContext, reason = "history_sync_indicator_retry")
        Toast.makeText(activity, R.string.home_sync_retry_scheduled, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun badgeBackground(hasIssue: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(9).toFloat()
        setColor(if (hasIssue) Color.rgb(229, 57, 53) else Color.rgb(25, 118, 210))
    }

    private data class IndicatorViews(
        val container: FrameLayout,
        val icon: ImageView,
        val badge: TextView,
    )

    private companion object {
        const val RECHECK_DELAY_MS = 1_500L
    }
}
