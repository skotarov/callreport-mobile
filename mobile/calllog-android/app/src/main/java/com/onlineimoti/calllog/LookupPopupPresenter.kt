package com.onlineimoti.calllog

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

internal object LookupPopupPresenter {
    fun show(
        context: Context,
        result: LookupResult,
        fullscreen: Boolean = false,
        phone: String = "",
        direction: String = "",
        /** Incoming-call coordinator already requested history rows in parallel. */
        remoteRowsArePreloaded: Boolean = false,
        /** Non-empty only for the single progressive popup opened for one active call. */
        popupSessionId: String = "",
        progressiveRows: IncomingCallPopupProgress? = null,
        /** True for enrichment intents; they must never resurrect a dismissed popup. */
        updateOnly: Boolean = false,
    ) {
        if (popupSessionId.isNotBlank() && IncomingCallPopupSessionStore.isDismissed(popupSessionId)) return

        val config = ConfigStore.load(context)
        val screenLocked = isScreenLocked(context)
        if (config.useOverlayPopups && config.useCustomStartPopup && Settings.canDrawOverlays(context) && !screenLocked) {
            val overlayIntent = Intent(context, PostCallOverlayService::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_LOOKUP)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, result.title)
                .putExtra(PostCallOverlayService.EXTRA_SUBTITLE, result.subtitle)
                .putStringArrayListExtra(PostCallOverlayService.EXTRA_LINES, ArrayList(result.lines))
                .putExtra(PostCallOverlayService.EXTRA_FORM_URL, "")
                .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
                .putExtra(PostCallOverlayService.EXTRA_REMOTE_ROWS_ARE_PRELOADED, remoteRowsArePreloaded)
                .putExtra(PostCallOverlayService.EXTRA_POPUP_SESSION_ID, popupSessionId)
                .putExtra(PostCallOverlayService.EXTRA_POPUP_UPDATE_ONLY, updateOnly)
            progressiveRows?.let { rows ->
                overlayIntent
                    .putExtra(PostCallOverlayService.EXTRA_PROGRESS_CALLS, rows.calls)
                    .putExtra(PostCallOverlayService.EXTRA_PROGRESS_LOCAL_NOTES, rows.localNotes)
                    .putExtra(PostCallOverlayService.EXTRA_PROGRESS_SERVER_NOTES, rows.serverNotes)
            }
            context.startService(overlayIntent)

            // The shade notification is a one-time fallback. Progressive enrichment
            // updates only the existing overlay rows and must not alert again.
            if (!updateOnly) {
                CallReportRuntime.showLookupShadeNotification(
                    context = context,
                    result = result,
                    phone = phone,
                    direction = direction,
                    incomingPopupDataIsPreloaded = remoteRowsArePreloaded,
                )
                if (phone.isNotBlank()) {
                    CallPopupTracker.markPopupOpened(context, phone, direction)
                }
            }
            return
        }

        CallReportRuntime.showLookupNotification(
            context = context,
            result = result,
            fullscreen = fullscreen && screenLocked,
            phone = phone,
            direction = direction,
        )
    }

    private fun isScreenLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isKeyguardLocked == true
    }
}
