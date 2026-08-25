package com.onlineimoti.calllog

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/** Small header signal for real local data that is still waiting to be uploaded. */
internal class HomeSyncStatusUi(
    private val activity: HomeActivity,
    private val binding: () -> ActivityHomeBinding,
    private val executor: ExecutorService,
) {
    private val generation = AtomicInteger(0)

    fun refresh() {
        if (activity.isFinishing || activity.isDestroyed) return
        val indicator = ensureIndicator() ?: return
        val appContext = activity.applicationContext

        // Worker state is intentionally ignored here. The cloud represents only real
        // durable local mutations that still exist in an outbox.
        val pendingNow = PendingSyncStatus.summary(appContext)
        render(indicator, pendingNow)

        val expectedGeneration = generation.incrementAndGet()
        runCatching {
            executor.execute {
                val pending = PendingSyncStatus.summary(appContext)
                activity.runOnUiThread {
                    if (expectedGeneration != generation.get() || activity.isFinishing || activity.isDestroyed) {
                        return@runOnUiThread
                    }
                    val current = ensureIndicator() ?: return@runOnUiThread
                    render(current, pending)
                    if (pending.count > 0) {
                        // Outboxes drain asynchronously. Re-check only while data is pending so
                        // the cloud disappears immediately after the last acknowledgement.
                        current.container.postDelayed({ refresh() }, RECHECK_DELAY_MS)
                    }
                }
            }
        }
    }

    private fun render(indicator: IndicatorViews, summary: PendingSyncSummary) {
        val state = homeSyncStatusState(summary.count, summary.failure.isNotBlank())
        indicator.container.visibility = if (state.visible) View.VISIBLE else View.GONE
        if (!state.visible) {
            indicator.badge.text = ""
            return
        }
        indicator.badge.text = state.badgeText
        indicator.icon.setImageResource(
            if (state.hasIssue) R.drawable.ic_cloud_sync_issue else R.drawable.ic_cloud_sync_pending,
        )
        indicator.badge.background = badgeBackground(state.hasIssue)
        indicator.container.contentDescription = activity.getString(
            if (state.hasIssue) R.string.home_sync_issue_content_description else R.string.home_sync_pending_content_description,
            state.badgeText,
        )
        indicator.container.setOnClickListener { showDetails(summary) }
    }

    private fun ensureIndicator(): IndicatorViews? {
        val views = binding()
        val parent = views.searchButton.parent as? ViewGroup ?: return null
        parent.findViewWithTag<FrameLayout>(TAG)?.let { existing ->
            val icon = existing.getChildAt(0) as? ImageView ?: return null
            val badge = existing.findViewWithTag<TextView>(BADGE_TAG) ?: return null
            return IndicatorViews(existing, icon, badge)
        }

        val size = dp(36)
        val container = FrameLayout(activity).apply {
            tag = TAG
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(4)
            }
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            setImageResource(R.drawable.ic_cloud_sync_pending)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val badge = TextView(activity).apply {
            tag = BADGE_TAG
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
            background = badgeBackground(hasIssue = false)
        }
        container.addView(icon)
        container.addView(badge)
        val searchIndex = parent.indexOfChild(views.searchButton).coerceAtLeast(0)
        parent.addView(container, searchIndex)
        return IndicatorViews(container, icon, badge)
    }

    private fun badgeBackground(hasIssue: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(9).toFloat()
        setColor(if (hasIssue) Color.rgb(229, 57, 53) else Color.rgb(25, 118, 210))
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
        runCatching {
            executor.execute {
                CallReportNoteOutboxScheduler.enqueue(appContext, reason = "home_sync_indicator_retry")
                CallReportTopicNoteOutbox.requestSyncNow(appContext)
                CompanyCallNoteOutbox.requestSyncNow(appContext)
                AccountMutationOutbox.schedulePending(appContext, replace = true)
                CallReportSyncScheduler.enqueueCatchUp(appContext, reason = "home_sync_indicator_retry")
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(activity, R.string.home_sync_retry_scheduled, Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private data class IndicatorViews(
        val container: FrameLayout,
        val icon: ImageView,
        val badge: TextView,
    )

    private companion object {
        const val TAG = "relationship_manager_home_sync_status"
        const val BADGE_TAG = "relationship_manager_home_sync_status_badge"
        const val RECHECK_DELAY_MS = 1_500L
    }
}
