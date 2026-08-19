package com.onlineimoti.calllog

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.CallLog

/** Watches Android's Call Log provider and debounces the insert/update notifications. */
internal class HomeCallLogObserverController(
    context: Context,
    private val handler: Handler,
    private val onCallLogChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private var registered = false
    private val refreshRunnable = Runnable(onCallLogChanged)
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = scheduleProviderRefresh()

        override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleProviderRefresh()
    }

    fun register() {
        if (registered || !PhoneCallReader.hasCallLogPermission(appContext)) return
        runCatching {
            appContext.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                observer,
            )
            registered = true
        }
    }

    /** Rechecks after Android has had time to publish a just-ended call-log row. */
    fun scheduleSettledRefresh() {
        schedule(MANUAL_SETTLE_DELAY_MS)
    }

    fun unregister() {
        handler.removeCallbacks(refreshRunnable)
        if (!registered) return
        runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        registered = false
    }

    private fun scheduleProviderRefresh() {
        schedule(PROVIDER_SETTLE_DELAY_MS)
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, delayMs)
    }

    private companion object {
        // ContentObserver fires after the provider mutation; keep only a short debounce
        // to collapse duplicate notifications without making a newly ended call feel late.
        const val PROVIDER_SETTLE_DELAY_MS = 180L
        // Keep the explicit post-call retry as a conservative fallback for devices that
        // publish the final Call Log row unusually late.
        const val MANUAL_SETTLE_DELAY_MS = 1_500L
    }
}
