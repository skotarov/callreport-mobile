package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.concurrent.Executors

/** Bridges Android's default-SMS delivery callback to the existing SMS inbox notification. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty()
        val phone = messages.firstOrNull()?.originatingAddress.orEmpty().trim()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }.trim()
        val receivedAt = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        if (phone.isBlank() || body.isBlank()) return

        val pendingResult = goAsync()
        WORKER.execute {
            try {
                val appContext = context.applicationContext
                saveToInboxIfMissing(appContext, phone, body, receivedAt)
                SmsIncomingNotifications.show(appContext, phone, body, receivedAt)
                // The provider row is now available. Privacy is checked again inside
                // the worker before any metadata or SMS text leaves the phone.
                CallReportSyncScheduler.enqueueCatchUp(
                    appContext,
                    reason = "sms_received",
                    initialDelayMillis = 500L,
                )
                appContext.sendBroadcast(
                    Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(appContext.packageName),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun saveToInboxIfMissing(context: Context, phone: String, body: String, receivedAt: Long) {
        if (isAlreadyStored(context, phone, body, receivedAt)) return
        runCatching {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, phone)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, receivedAt)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        }
    }

    /** Some device builds insert the row themselves; never show it twice in the inbox. */
    private fun isAlreadyStored(context: Context, phone: String, body: String, receivedAt: Long): Boolean {
        val earliest = receivedAt - DUPLICATE_WINDOW_MS
        return runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.BODY}=? AND ${Telephony.Sms.DATE}>=?",
                arrayOf(phone, body, earliest.toString()),
                null,
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private companion object {
        private const val DUPLICATE_WINDOW_MS = 10_000L
        private val WORKER = Executors.newSingleThreadExecutor()
    }
}
