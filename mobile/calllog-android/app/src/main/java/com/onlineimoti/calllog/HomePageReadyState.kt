package com.onlineimoti.calllog

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Tracks whether the visible Home page has finished attaching its notes. */
internal object HomePageReadyState {
    private val ready = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var onReady: (() -> Unit)? = null
    private var activityRef: WeakReference<Activity>? = null
    private var busyToken = 0L

    fun attach(activity: Activity) = synchronized(lock) {
        activityRef = WeakReference(activity)
    }

    fun detach(activity: Activity) = synchronized(lock) {
        if (activityRef?.get() !== activity) return@synchronized
        finishBusyLocked()
        activityRef?.clear()
        activityRef = null
    }

    fun markLoading() {
        ready.set(false)
        synchronized(lock) {
            finishBusyLocked()
            activityRef?.get()?.let { activity ->
                busyToken = HomeBusyTooltipUi.begin(activity, HomeBusyWork.CALLS)
            }
        }
    }

    /**
     * The call rows are already usable even while names and notes continue loading.
     * Stop only the primary calls indicator without claiming enrichment is complete.
     */
    fun markRowsVisible() {
        synchronized(lock) { finishBusyLocked() }
    }

    fun markReady() {
        ready.set(true)
        synchronized(lock) { finishBusyLocked() }
        onReady?.invoke()
    }

    fun isReady(): Boolean = ready.get()
    fun setOnReady(listener: () -> Unit) { onReady = listener }
    fun clearOnReady() { onReady = null }

    private fun finishBusyLocked() {
        val token = busyToken
        busyToken = 0L
        activityRef?.get()?.let { activity -> HomeBusyTooltipUi.end(activity, token) }
    }
}
