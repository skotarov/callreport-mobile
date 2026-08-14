package com.onlineimoti.calllog

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends an SMS from Relationship Manager while it is the default SMS app.
 *
 * A row in the local SMS history is added only after Android reports that the modem/operator
 * accepted every SMS part. This is a send confirmation, not a recipient-delivery receipt.
 */
internal object SmsMessageSender {
    data class Outcome(
        val historySaved: Boolean,
    )

    fun send(
        context: Context,
        rawPhone: String,
        rawBody: String,
        subscriptionId: Int? = null,
    ): Result<Outcome> = runCatching {
        val appContext = context.applicationContext
        val phone = PhoneNormalizer.normalize(rawPhone).ifBlank { rawPhone.trim() }
        val body = rawBody.trim()
        require(phone.isNotBlank()) { "Липсва телефонен номер." }
        require(body.isNotBlank()) { "Напиши съобщение." }
        require(DefaultSmsRoleController.isDefaultSmsApp(appContext)) {
            "Първо избери Relationship Manager като SMS приложение."
        }
        require(
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED,
        ) {
            "Липсва разрешение за изпращане на SMS. Отвори Settings → Permissions и разреши SMS."
        }

        val manager = smsManagerForSubscription(subscriptionId)
        val parts = manager.divideMessage(body)
        require(parts.isNotEmpty()) { "Не успях да подготвя текста за изпращане." }

        sendAndAwaitConfirmation(appContext, manager, phone, body, parts)
        val historySaved = saveToSystemSentMessages(appContext, phone, body)
        if (historySaved) {
            // The sync worker performs the final privacy check. Known personal
            // contacts that are not CRM/care remain entirely on this device.
            CallReportSyncScheduler.enqueueCatchUp(
                appContext,
                reason = "sms_sent",
                initialDelayMillis = 500L,
            )
        }
        Outcome(historySaved = historySaved)
    }

    private fun sendAndAwaitConfirmation(
        context: Context,
        manager: SmsManager,
        phone: String,
        body: String,
        parts: ArrayList<String>,
    ) {
        val sentAction = "$ACTION_SMS_SENT.${UUID.randomUUID()}"
        val pendingParts = CountDownLatch(parts.size)
        val failureCode = AtomicReference<Int?>(null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != sentAction) return
                if (resultCode != Activity.RESULT_OK) {
                    failureCode.compareAndSet(null, resultCode)
                }
                pendingParts.countDown()
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(sentAction),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        try {
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val requestCodeBase = (System.nanoTime() and 0x3fffffff).toInt()
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                sentIntents += PendingIntent.getBroadcast(
                    context,
                    requestCodeBase + index,
                    Intent(sentAction)
                        .setPackage(context.packageName)
                        .putExtra(EXTRA_PART_INDEX, index),
                    flags,
                )
            }

            if (parts.size > 1) {
                manager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
            } else {
                manager.sendTextMessage(phone, null, body, sentIntents.first(), null)
            }

            check(pendingParts.await(SMS_SENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Няма потвърждение за изпращане от телефона. SMS не е отбелязан като изпратен."
            }
            failureCode.get()?.let { code ->
                error(errorMessageFor(code))
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun smsManagerForSubscription(requestedSubscriptionId: Int?): SmsManager {
        return runCatching {
            val subscriptionId = requestedSubscriptionId
                ?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
                ?: SubscriptionManager.getDefaultSmsSubscriptionId()
            if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        }.getOrElse {
            SmsManager.getDefault()
        }
    }

    private fun errorMessageFor(code: Int): String {
        return when (code) {
            SmsManager.RESULT_ERROR_NO_SERVICE -> "Няма мобилна мрежа за изпращане на SMS."
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Мобилната мрежа е изключена."
            SmsManager.RESULT_ERROR_NULL_PDU -> "Телефонът не успя да подготви SMS."
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Достигнат е лимитът за изпращане на SMS."
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Операторът не разрешава SMS към този номер."
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Телефонът или операторът отказа изпращането на SMS."
            else -> "Неуспешно изпращане на SMS (код $code)."
        }
    }

    private fun saveToSystemSentMessages(context: Context, phone: String, body: String): Boolean {
        return runCatching {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, phone)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.DATE_SENT, now)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values) != null
        }.getOrDefault(false)
    }

    private const val ACTION_SMS_SENT = "com.onlineimoti.calllog.SMS_SENT"
    private const val EXTRA_PART_INDEX = "part_index"
    private const val SMS_SENT_TIMEOUT_SECONDS = 45L
}
