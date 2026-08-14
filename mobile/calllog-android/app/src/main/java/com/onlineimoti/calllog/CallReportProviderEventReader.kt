package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * Sync-only reader. It deliberately does not change the existing Home readers or UI model.
 * It reads a bounded recent window. SMS text is read only so the privacy policy can decide
 * whether the exact company-visible communication may be uploaded.
 */
internal object CallReportProviderEventReader {
    internal data class PhoneEvent(
        val providerId: String,
        val phone: String,
        val contactName: String,
        val direction: String,
        val status: String,
        val occurredAtMs: Long,
        val durationSeconds: Long,
    )

    internal data class SmsEvent(
        val providerId: String,
        val phone: String,
        val direction: String,
        val status: String,
        val occurredAtMs: Long,
        val body: String,
    )

    fun recentPhoneEvents(context: Context, limit: Int): List<PhoneEvent> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        return runCatching {
            buildList {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(CallLog.Calls._ID)
                    val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                    val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                    val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                    val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)
                    while (cursor.moveToNext() && size < limit.coerceIn(1, 500)) {
                        val phone = cursor.getString(numberIndex).orEmpty().trim()
                        val id = cursor.getLong(idIndex).toString()
                        val occurredAt = cursor.getLong(dateIndex)
                        if (phone.isBlank() || id.isBlank() || occurredAt <= 0L) continue
                        val meta = phoneMeta(cursor.getInt(typeIndex))
                        add(
                            PhoneEvent(
                                providerId = id,
                                phone = phone,
                                contactName = cursor.getString(nameIndex).orEmpty().trim(),
                                direction = meta.direction,
                                status = meta.status,
                                occurredAtMs = occurredAt,
                                durationSeconds = cursor.getLong(durationIndex).coerceAtLeast(0L),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun recentSmsEvents(context: Context, limit: Int): List<SmsEvent> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.BODY,
        )
        return runCatching {
            buildList {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${Telephony.Sms.DATE} DESC",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(Telephony.Sms._ID)
                    val addressIndex = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                    val dateIndex = cursor.getColumnIndex(Telephony.Sms.DATE)
                    val typeIndex = cursor.getColumnIndex(Telephony.Sms.TYPE)
                    val bodyIndex = cursor.getColumnIndex(Telephony.Sms.BODY)
                    while (cursor.moveToNext() && size < limit.coerceIn(1, 500)) {
                        val phone = cursor.getString(addressIndex).orEmpty().trim()
                        val id = cursor.getLong(idIndex).toString()
                        val occurredAt = cursor.getLong(dateIndex)
                        if (phone.isBlank() || id.isBlank() || occurredAt <= 0L) continue
                        val meta = smsMeta(cursor.getInt(typeIndex))
                        add(
                            SmsEvent(
                                providerId = id,
                                phone = phone,
                                direction = meta.direction,
                                status = meta.status,
                                occurredAtMs = occurredAt,
                                body = cursor.getString(bodyIndex).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private data class EventMeta(val direction: String, val status: String)

    private fun phoneMeta(type: Int): EventMeta = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> EventMeta("out", "answered")
        CallLog.Calls.MISSED_TYPE -> EventMeta("in", "missed")
        CallLog.Calls.REJECTED_TYPE -> EventMeta("in", "rejected")
        CallLog.Calls.BLOCKED_TYPE -> EventMeta("in", "blocked")
        else -> EventMeta("in", "answered")
    }

    private fun smsMeta(type: Int): EventMeta = when (type) {
        Telephony.Sms.MESSAGE_TYPE_SENT,
        Telephony.Sms.MESSAGE_TYPE_OUTBOX,
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> EventMeta("out", "sent")
        Telephony.Sms.MESSAGE_TYPE_FAILED -> EventMeta("out", "failed")
        else -> EventMeta("in", "received")
    }
}
