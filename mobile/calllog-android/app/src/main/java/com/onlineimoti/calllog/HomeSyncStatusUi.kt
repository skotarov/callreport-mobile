package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import org.json.JSONArray
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
        val pendingNow = pendingUploadCount(appContext)
        render(indicator, pendingNow)

        val expectedGeneration = generation.incrementAndGet()
        runCatching {
            executor.execute {
                val pending = pendingUploadCount(appContext)
                activity.runOnUiThread {
                    if (expectedGeneration != generation.get() || activity.isFinishing || activity.isDestroyed) {
                        return@runOnUiThread
                    }
                    val current = ensureIndicator() ?: return@runOnUiThread
                    render(current, pending)
                    if (pending > 0) {
                        // Outboxes drain asynchronously. Re-check only while data is pending so
                        // the cloud disappears immediately after the last acknowledgement.
                        current.container.postDelayed({ refresh() }, RECHECK_DELAY_MS)
                    }
                }
            }
        }
    }

    private fun pendingUploadCount(context: Context): Int {
        val mainNotes = legacyMainNotePendingCount(context)
        return mainNotes +
            CallReportTopicNoteOutbox.pendingCount(context) +
            CompanyCallNoteOutbox.pendingClientEventIds(context).size +
            AccountMutationOutbox.pendingCountForCurrentAccount(context)
    }

    /**
     * Compatibility read for the existing durable main-note queue. The preference name and
     * payload are persisted app data and must not be renamed without a migration.
     */
    private fun legacyMainNotePendingCount(context: Context): Int {
        val raw = context.applicationContext
            .getSharedPreferences(LEGACY_MAIN_NOTE_OUTBOX_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_MAIN_NOTE_OUTBOX_OPERATIONS, "[]")
            .orEmpty()
        return runCatching { JSONArray(raw).length() }.getOrDefault(0)
    }

    private fun render(indicator: IndicatorViews, pendingCount: Int) {
        val state = homeSyncStatusState(pendingCount)
        indicator.container.visibility = if (state.visible) View.VISIBLE else View.GONE
        if (!state.visible) {
            indicator.badge.text = ""
            return
        }
        indicator.badge.text = state.badgeText
        indicator.container.contentDescription = activity.getString(
            R.string.home_sync_pending_content_description,
            state.badgeText,
        )
    }

    private fun ensureIndicator(): IndicatorViews? {
        val views = binding()
        val parent = views.searchButton.parent as? ViewGroup ?: return null
        parent.findViewWithTag<FrameLayout>(TAG)?.let { existing ->
            val badge = existing.findViewWithTag<TextView>(BADGE_TAG) ?: return null
            return IndicatorViews(existing, badge)
        }

        val size = dp(36)
        val container = FrameLayout(activity).apply {
            tag = TAG
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(4)
            }
            visibility = View.GONE
            isClickable = false
            isFocusable = false
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9).toFloat()
                setColor(Color.rgb(229, 57, 53))
            }
        }
        container.addView(icon)
        container.addView(badge)
        val searchIndex = parent.indexOfChild(views.searchButton).coerceAtLeast(0)
        parent.addView(container, searchIndex)
        return IndicatorViews(container, badge)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private data class IndicatorViews(
        val container: FrameLayout,
        val badge: TextView,
    )

    private companion object {
        const val TAG = "relationship_manager_home_sync_status"
        const val BADGE_TAG = "relationship_manager_home_sync_status_badge"
        const val RECHECK_DELAY_MS = 1_500L

        // Storage identifiers are intentionally kept for backward compatibility.
        const val LEGACY_MAIN_NOTE_OUTBOX_PREFS = "callreport_note_outbox"
        const val LEGACY_MAIN_NOTE_OUTBOX_OPERATIONS = "operations_v1"
    }
}
