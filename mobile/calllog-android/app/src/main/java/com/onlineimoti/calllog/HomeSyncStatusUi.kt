package com.onlineimoti.calllog

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
        val button = ensureButton() ?: return
        val appContext = activity.applicationContext

        // Show the indicator only for real pending local mutations. A WorkManager job may
        // legitimately remain ENQUEUED/BLOCKED while there is nothing left to upload, so
        // worker state must not be treated as pending data.
        val pendingNow = hasPendingUploads(appContext)
        button.visibility = if (pendingNow) View.VISIBLE else View.GONE

        val expectedGeneration = generation.incrementAndGet()
        runCatching {
            executor.execute {
                val pending = hasPendingUploads(appContext)
                activity.runOnUiThread {
                    if (expectedGeneration != generation.get() || activity.isFinishing || activity.isDestroyed) {
                        return@runOnUiThread
                    }
                    val currentButton = ensureButton() ?: return@runOnUiThread
                    currentButton.visibility = if (pending) View.VISIBLE else View.GONE
                    if (pending) {
                        // Outboxes are drained asynchronously. Re-check only while there is
                        // something pending so the icon disappears without requiring navigation.
                        currentButton.postDelayed({ refresh() }, RECHECK_DELAY_MS)
                    }
                }
            }
        }
    }

    private fun hasPendingUploads(context: android.content.Context): Boolean =
        CallReportNoteOutbox.hasPending(context) ||
            CallReportTopicNoteOutbox.hasPending(context) ||
            CompanyCallNoteOutbox.hasPending(context) ||
            AccountMutationOutbox.pendingCountForCurrentAccount(context) > 0

    private fun ensureButton(): ImageButton? {
        val views = binding()
        val parent = views.searchButton.parent as? ViewGroup ?: return null
        parent.findViewWithTag<ImageButton>(TAG)?.let { return it }

        val size = dp(36)
        val button = ImageButton(activity).apply {
            tag = TAG
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(4)
            }
            setImageResource(R.drawable.ic_cloud_sync_pending)
            background = null
            contentDescription = activity.getString(R.string.home_sync_pending_content_description)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        val searchIndex = parent.indexOfChild(views.searchButton).coerceAtLeast(0)
        parent.addView(button, searchIndex)
        return button
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "relationship_manager_home_sync_status"
        const val RECHECK_DELAY_MS = 1_500L
    }
}
