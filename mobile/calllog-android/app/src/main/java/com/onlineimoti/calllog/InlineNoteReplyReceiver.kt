package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

class InlineNoteReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(CallReportNotifications.KEY_INLINE_NOTE_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (noteText.isBlank()) return

        val phone = intent.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty()
        val saved = phone.isNotBlank() && CallNoteWriter.writeCallOrGeneral(
            context = context,
            phone = phone,
            text = noteText,
            direction = intent.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty(),
            callAt = intent.getLongExtra(PostCallOverlayService.EXTRA_CALL_AT, 0L),
            durationSeconds = intent.getLongExtra(PostCallOverlayService.EXTRA_DURATION, 0L),
            actionIssuedAt = intent.getLongExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, 0L),
            // The notification input is a call-note action, never a main-note fallback.
            syncToCrm = false,
        ).saved

        if (saved) {
            context.sendBroadcast(Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(context.packageName))
            NotificationManagerCompat.from(context).cancel(CallReportNotifications.LOOKUP_NOTIFICATION_ID)
            NotificationManagerCompat.from(context).cancel(CallReportRuntime.POST_CALL_NOTIFICATION_ID)
        }
        Toast.makeText(context, if (saved) "Бележката е записана" else "Не успях да запиша бележката", Toast.LENGTH_SHORT).show()
    }
}
