package com.onlineimoti.calllog

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/** Small header signal for data that is not fully synchronized with the server yet. */
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

        // Queue state is local and cheap, so reflect a freshly saved note immediately.
        button.visibility = if (hasPendingUploads(appContext)) View.VISIBLE else View.GONE

        val expectedGeneration = generation.incrementAndGet()
        runCatching {
            executor.execute {
                val pending = hasPendingUploads(appContext) ||
                    CallReportSyncScheduler.hasIncompleteCatchUp(appContext)
                activity.runOnUiThread {
                    if (expectedGeneration != generation.get() || activity.isFinishing || activity.isDestroyed) {
                        return@runOnUiThread
                    }
                    ensureButton()?.visibility = if (pending) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun hasPendingUploads(context: android.content.Context): Boolean =
        CallReportNoteOutbox.hasPending(context) ||
            CallReportTopicNoteOutbox.hasPending(context) ||
            CompanyCallNoteOutbox.hasPending(context)

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
            scaleType = ImageButton.ScaleType.CENTER_INSIDE
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
    }
}
