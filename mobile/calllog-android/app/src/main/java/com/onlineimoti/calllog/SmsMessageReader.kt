package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat

internal data class SmsMessageRecord(
    val body: String,
    val timestampMs: Long,
    val type: Int,
    val providerId: String = "",
) {
    val isOutgoing: Boolean
        get() = type == Telephony.Sms.MESSAGE_TYPE_SENT || type == Telephony.Sms.MESSAGE_TYPE_OUTBOX

    val directionLabel: String
        get() = if (isOutgoing) "изпратено" else "получено"
}

/** A single row from the device-wide SMS timeline. */
internal data class SmsTimelineMessage(
    val address: String,
    val body: String,
    val timestampMs: Long,
    val type: Int,
    val providerId: String,
) {
    val isOutgoing: Boolean
        get() = type == Telephony.Sms.MESSAGE_TYPE_SENT || type == Telephony.Sms.MESSAGE_TYPE_OUTBOX

    val directionLabel: String
        get() = if (isOutgoing) "изпратено" else "получено"
}

/** Reads only the SMS rows needed by the current screen or search request. */
internal object SmsMessageReader {
    private const val MAX_MESSAGES_PER_CONTACT = 150

    fun hasReadSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    fun messagesForPhone(context: Context, phone: String, limit: Int = MAX_MESSAGES_PER_CONTACT): List<SmsMessageRecord> {
        val target = CommunicationAddress.from(phone)
        if (!target.isValid || !canReadSms(context)) return emptyList()

        return runCatching {
            if (!target.isPhone) {
                queryMessages(
                    context = context,
                    selection = "${Telephony.Sms.ADDRESS} = ?",
                    selectionArgs = arrayOf(target.raw),
                    target = target,
                    limit = limit,
                )
            } else {
                val candidates = PhoneNormalizer.candidates(phone)
                val exact = queryMessages(
                    context = context,
                    selection = "${Telephony.Sms.ADDRESS} IN (${candidates.joinToString(",") { "?" }})",
                    selectionArgs = candidates.toTypedArray(),
                    target = target,
                    limit = limit,
                )
                if (exact.isNotEmpty()) {
                    exact
                } else {
                    val lastDigits = target.phoneKey.takeLast(9)
                    if (lastDigits.length < 7) emptyList() else queryMessages(
                        context = context,
                        selection = "${Telephony.Sms.ADDRESS} LIKE ?",
                        selectionArgs = arrayOf("%$lastDigits"),
                        target = target,
                        limit = limit,
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Searches the device SMS store by independent text/number terms. A query
     * such as "оглед Петров" requires both terms but they may occur anywhere
     * and in any order, including one in the SMS and one in the phone number.
     */
    fun searchMessages(context: Context, query: String, limit: Int): List<SmsTimelineMessage> {
        val terms = SearchQueryTerms.from(query)
        if (terms.isEmpty || !canReadSms(context)) return emptyList()
        val safeLimit = limit.coerceIn(1, 500)

        // Use the message provider to narrow ordinary text searches, while pure
        // numeric terms are validated after the rows are read. This preserves
        // matching across phone formats such as 08… and +359….
        val selectionArgs = mutableListOf<String>()
        val clauses = terms.textTerms().map { term ->
            selectionArgs += "%$term%"
            selectionArgs += "%$term%"
            "(${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?)"
        }
        val selection = clauses.joinToString(" AND ").ifBlank { "1=1" }

        return runCatching {
            queryTimelineMessages(
                context = context,
                selection = selection,
                selectionArgs = selectionArgs.toTypedArray(),
                limit = safeLimit,
            ).filter { message ->
                terms.matches(message.body, message.address)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Reads a single chronological window from the device SMS store. The caller
     * requests one extra row to determine whether a next page exists.
     */
    fun recentMessages(context: Context, offset: Int, limit: Int): List<SmsTimelineMessage> {
        if (!canReadSms(context)) return emptyList()
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 101)
        return runCatching {
            val projection = timelineProjection()
            val rows = mutableListOf<SmsTimelineMessage>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                var skipped = 0
                while (cursor.moveToNext()) {
                    if (skipped < safeOffset) {
                        skipped++
                        continue
                    }
                    rows += cursor.timelineMessage(idIndex, addressIndex, bodyIndex, dateIndex, typeIndex)
                    if (rows.size >= safeLimit) break
                }
            }
            rows
        }.getOrDefault(emptyList())
    }

    private fun queryTimelineMessages(
        context: Context,
        selection: String,
        selectionArgs: Array<String>,
        limit: Int,
    ): List<SmsTimelineMessage> {
        val rows = mutableListOf<SmsTimelineMessage>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            timelineProjection(),
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (cursor.moveToNext() && rows.size < limit) {
                rows += cursor.timelineMessage(idIndex, addressIndex, bodyIndex, dateIndex, typeIndex)
            }
        }
        return rows
    }

    private fun timelineProjection(): Array<String> = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
    )

    private fun android.database.Cursor.timelineMessage(
        idIndex: Int,
        addressIndex: Int,
        bodyIndex: Int,
        dateIndex: Int,
        typeIndex: Int,
    ): SmsTimelineMessage = SmsTimelineMessage(
        address = getString(addressIndex).orEmpty(),
        body = getString(bodyIndex).orEmpty().trim(),
        timestampMs = getLong(dateIndex),
        type = getInt(typeIndex),
        providerId = getLong(idIndex).toString(),
    )

    private fun queryMessages(
        context: Context,
        selection: String,
        selectionArgs: Array<String>,
        target: CommunicationAddress,
        limit: Int,
    ): List<SmsMessageRecord> {
        val projection = timelineProjection()
        val rows = mutableListOf<SmsMessageRecord>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (cursor.moveToNext() && rows.size < limit) {
                val address = cursor.getString(addressIndex).orEmpty()
                if (!target.matches(address)) continue
                rows += SmsMessageRecord(
                    body = cursor.getString(bodyIndex).orEmpty().trim(),
                    timestampMs = cursor.getLong(dateIndex),
                    type = cursor.getInt(typeIndex),
                    providerId = cursor.getLong(idIndex).toString(),
                )
            }
        }
        return rows
    }

    private fun canReadSms(context: Context): Boolean = hasReadSmsPermission(context)
}
