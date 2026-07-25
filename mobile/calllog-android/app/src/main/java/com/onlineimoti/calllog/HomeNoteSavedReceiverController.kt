package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

internal class HomeNoteSavedReceiverController(
    private val activity: HomeActivity,
    private val onNoteSaved: () -> Unit,
) {
    private var registered = false
    private var handledRevision = HomeNoteChangeSignal.current(activity)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handledRevision = maxOf(handledRevision, HomeNoteChangeSignal.current(activity))
            invalidateHomeSources()
            onNoteSaved()
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            // Legacy editor result action.
            addAction(HomeActivity.ACTION_CONTACT_NOTE_SAVED)
            // Current popup and editor action, emitted after a note is actually saved.
            addAction(PostCallOverlayService.ACTION_NOTES_CHANGED)
        }
        ContextCompat.registerReceiver(
            activity,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true

        // A save can happen while Home is paused and this receiver is unregistered.
        // Consume that durable revision before Home's normal onResume render starts.
        val currentRevision = HomeNoteChangeSignal.current(activity)
        if (currentRevision > handledRevision) {
            handledRevision = currentRevision
            invalidateHomeSources()
        }
    }

    fun unregister() {
        if (!registered) return
        runCatching { activity.unregisterReceiver(receiver) }
        registered = false
    }

    private fun invalidateHomeSources() {
        HomeCallPageLoader.clearSearchCache()
        HomeTimelineLoader.invalidateCache()
    }
}
