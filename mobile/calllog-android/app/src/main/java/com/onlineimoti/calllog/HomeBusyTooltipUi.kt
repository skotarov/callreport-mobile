package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** Describes background work currently responsible for a visible screen delay. */
internal enum class HomeBusyWork {
    CALLS,
    CRM_CALLS,
    SEARCH,
    CLIENTS,
    FULL_LOG,
    MORE_CALLS,
    LOCAL_NOTES,
    SERVER_NOTES,
    COMPANY_DATA,
    HISTORY_LOCAL,
    HISTORY_SERVER,
    HISTORY_PREPARE,
}

internal object HomeBusyText {
    fun value(work: HomeBusyWork, bulgarian: Boolean): String = if (bulgarian) {
        when (work) {
            HomeBusyWork.CALLS -> "Зареждам разговорите…"
            HomeBusyWork.CRM_CALLS -> "Обработвам CRM разговорите…"
            HomeBusyWork.SEARCH -> "Търся в разговори и бележки…"
            HomeBusyWork.CLIENTS -> "Зареждам клиентите…"
            HomeBusyWork.FULL_LOG -> "Зареждам пълната история…"
            HomeBusyWork.MORE_CALLS -> "Зареждам още разговори…"
            HomeBusyWork.LOCAL_NOTES -> "Допълвам локалните бележки…"
            HomeBusyWork.SERVER_NOTES -> "Допълвам сървърните бележки…"
            HomeBusyWork.COMPANY_DATA -> "Обновявам фирмените данни…"
            HomeBusyWork.HISTORY_LOCAL -> "Проверявам локално — възможна промяна…"
            HomeBusyWork.HISTORY_SERVER -> "Проверявам сървъра — възможна промяна…"
            HomeBusyWork.HISTORY_PREPARE -> "Прилагам обновената история…"
        }
    } else {
        when (work) {
            HomeBusyWork.CALLS -> "Loading calls…"
            HomeBusyWork.CRM_CALLS -> "Processing CRM calls…"
            HomeBusyWork.SEARCH -> "Searching calls and notes…"
            HomeBusyWork.CLIENTS -> "Loading clients…"
            HomeBusyWork.FULL_LOG -> "Loading full history…"
            HomeBusyWork.MORE_CALLS -> "Loading more calls…"
            HomeBusyWork.LOCAL_NOTES -> "Adding local notes…"
            HomeBusyWork.SERVER_NOTES -> "Adding server notes…"
            HomeBusyWork.COMPANY_DATA -> "Updating company data…"
            HomeBusyWork.HISTORY_LOCAL -> "Checking local data — changes may follow…"
            HomeBusyWork.HISTORY_SERVER -> "Checking server data — changes may follow…"
            HomeBusyWork.HISTORY_PREPARE -> "Applying the updated history…"
        }
    }
}

/**
 * Shows one non-blocking light overlay for concurrent background tasks.
 * Tokens prevent an older completion from hiding a newer operation.
 */
internal object HomeBusyTooltipUi {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextToken = AtomicLong(0L)
    private val sessions = WeakHashMap<Activity, Session>()

    fun begin(activity: Activity, work: HomeBusyWork): Long {
        val token = nextToken.incrementAndGet()
        mainHandler.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                sessions.getOrPut(activity) { Session(activity) }.begin(token, work)
            }
        }
        return token
    }

    fun end(activity: Activity, token: Long) {
        if (token <= 0L) return
        mainHandler.post { sessions[activity]?.end(token) }
    }

    fun clear(activity: Activity) {
        mainHandler.post { sessions.remove(activity)?.release() }
    }

    private class Session(activity: Activity) {
        private val activityRef = WeakReference(activity)
        private val tasks = linkedMapOf<Long, HomeBusyWork>()
        private var popup: PopupWindow? = null
        private var label: TextView? = null
        private var shownAtMs = 0L
        private val dismissRunnable = Runnable { dismissNow() }

        fun begin(token: Long, work: HomeBusyWork) {
            tasks.remove(token)
            tasks[token] = work
            mainHandler.removeCallbacks(dismissRunnable)
            showOrUpdate()
        }

        fun end(token: Long) {
            tasks.remove(token)
            if (tasks.isNotEmpty()) {
                showOrUpdate()
                return
            }
            val remaining = max(0L, MIN_VISIBLE_MS - (SystemClock.uptimeMillis() - shownAtMs))
            mainHandler.removeCallbacks(dismissRunnable)
            mainHandler.postDelayed(dismissRunnable, remaining)
        }

        fun release() {
            tasks.clear()
            mainHandler.removeCallbacks(dismissRunnable)
            dismissNow()
        }

        private fun showOrUpdate() {
            val activity = activityRef.get() ?: return
            val work = tasks.entries.lastOrNull()?.value ?: return
            val text = HomeBusyText.value(work, AppLocaleText.isBulgarian())
            label?.text = text
            val anchor = activity.findViewById<View>(android.R.id.content) ?: return
            if (popup?.isShowing == true) return
            if (!anchor.isAttachedToWindow || anchor.windowToken == null) {
                anchor.post { if (tasks.isNotEmpty()) showOrUpdate() }
                return
            }
            val content = TextView(activity).apply {
                this.text = text
                textSize = 12f
                includeFontPadding = false
                maxLines = 1
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(30, 41, 59))
                setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(232, 235, 239))
                    setStroke(dp(activity, 1), Color.rgb(203, 213, 225))
                    cornerRadius = dp(activity, 13).toFloat()
                }
                contentDescription = text
            }
            label = content
            popup = PopupWindow(
                content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(activity, TOOLTIP_HEIGHT_DP),
                false,
            ).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isTouchable = false
                isOutsideTouchable = false
                isClippingEnabled = true
                elevation = dp(activity, 9).toFloat()
                setOnDismissListener {
                    popup = null
                    label = null
                }
            }
            runCatching {
                popup?.showAtLocation(anchor, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
                shownAtMs = SystemClock.uptimeMillis()
            }.onFailure {
                popup = null
                label = null
            }
        }

        private fun dismissNow() {
            popup?.dismiss()
            popup = null
            label = null
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private const val TOOLTIP_HEIGHT_DP = 26
    private const val MIN_VISIBLE_MS = 320L
}
