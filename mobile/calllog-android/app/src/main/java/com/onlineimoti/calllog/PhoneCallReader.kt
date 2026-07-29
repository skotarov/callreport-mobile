package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhoneCallRecord(
    val number: String,
    val name: String,
    /** Broad direction, used by server sync and text labels. */
    val direction: String,
    val startedAt: Long,
    val durationSeconds: Long,
    val smsBody: String = "",
    /** Android CallLog/SMS provider row ID. Empty only for synthetic search rows. */
    val providerId: String = "",
    /**
     * Exact Android [CallLog.Calls.TYPE] value. Direction alone is insufficient:
     * MISSED, REJECTED and BLOCKED are all incoming, but need different icons.
     * Zero is reserved for synthetic/non-provider rows.
     */
    val callType: Int = 0,
    /** Server-provided search snippet that explains why a synthetic Clients result matched. */
    val searchSnippet: String = "",
) {
    val displayNumber: String
        get() = PhoneNormalizer.display(number)

    val displayName: String
        get() = name.ifBlank { displayNumber.ifBlank { number } }

    val isSms: Boolean
        get() = direction == "sms_in" || direction == "sms_out"

    val smsDirectionLabel: String
        get() = when {
            AppLocaleText.isBulgarian() && direction == "sms_out" -> "изпратено"
            AppLocaleText.isBulgarian() -> "получено"
            direction == "sms_out" -> "sent"
            else -> "received"
        }
}

object PhoneCallReader {
    /** Raw Android permission state, used only for settings/permission summaries. */
    fun hasRawCallLogPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Local Android call history depends only on the Android READ_CALL_LOG permission.
     * Server/company access is optional and must not block the local call log.
     */
    fun hasCallLogPermission(context: Context): Boolean {
        return hasRawCallLogPermission(context)
    }

    fun latestCall(context: Context): PhoneCallRecord? {
        return recentCalls(context, limit = 1).firstOrNull()
    }

    fun recentCalls(context: Context, limit: Int = 20, offset: Int = 0): List<PhoneCallRecord> {
        if (!hasCallLogPermission(context)) return emptyList()
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        return runCatching { readRecentCalls(context, safeLimit, safeOffset, phoneFilter = "") }.getOrElse { emptyList() }
    }

    fun callsForPhone(context: Context, phone: String, limit: Int = 50, offset: Int = 0): List<PhoneCallRecord> {
        if (!hasCallLogPermission(context) || !CommunicationAddress.from(phone).isPhone) return emptyList()
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        return runCatching { readRecentCalls(context, safeLimit, safeOffset, phoneFilter = phone) }.getOrElse { emptyList() }
    }

    private fun readRecentCalls(
        context: Context,
        safeLimit: Int,
        safeOffset: Int,
        phoneFilter: String,
    ): List<PhoneCallRecord> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val normalizedPhoneFilter = normalizePhone(phoneFilter)
        val directCandidates = if (normalizedPhoneFilter.isBlank()) emptyList() else phoneCandidates(phoneFilter)
        val directSelection = directCandidates.takeIf { it.isNotEmpty() }
            ?.let { "${CallLog.Calls.NUMBER} IN (${it.joinToString(",") { "?" }})" }

        val direct = queryCalls(
            context = context,
            projection = projection,
            safeLimit = safeLimit,
            safeOffset = safeOffset,
            normalizedPhoneFilter = normalizedPhoneFilter,
            selection = directSelection,
            selectionArgs = directCandidates.toTypedArray(),
        )
        if (normalizedPhoneFilter.isBlank() || direct.isNotEmpty()) return direct

        val suffix = normalizedPhoneFilter.takeLast(9)
        return if (suffix.length < 7) {
            emptyList()
        } else {
            queryCalls(
                context = context,
                projection = projection,
                safeLimit = safeLimit,
                safeOffset = safeOffset,
                normalizedPhoneFilter = normalizedPhoneFilter,
                selection = "${CallLog.Calls.NUMBER} LIKE ?",
                selectionArgs = arrayOf("%$suffix"),
            )
        }
    }

    private fun queryCalls(
        context: Context,
        projection: Array<String>,
        safeLimit: Int,
        safeOffset: Int,
        normalizedPhoneFilter: String,
        selection: String?,
        selectionArgs: Array<String>,
    ): List<PhoneCallRecord> {
        val sortOrder = "${CallLog.Calls.DATE} DESC"
        return buildList {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs.takeIf { it.isNotEmpty() },
                sortOrder,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)

                var skipped = 0
                while (cursor.moveToNext() && size < safeLimit) {
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                    if (number.isBlank()) continue
                    if (normalizedPhoneFilter.isNotBlank() && !samePhone(normalizedPhoneFilter, number)) continue
                    if (skipped < safeOffset) {
                        skipped += 1
                        continue
                    }

                    val cachedName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                    val type = if (typeIndex >= 0) cursor.getInt(typeIndex) else 0
                    val startedAt = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                    val duration = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L
                    val providerId = if (idIndex >= 0) cursor.getLong(idIndex).toString() else ""
                    add(
                        PhoneCallRecord(
                            number = number,
                            name = cachedName,
                            direction = directionFromType(type),
                            startedAt = startedAt,
                            durationSeconds = duration,
                            providerId = providerId,
                            callType = type,
                        ),
                    )
                }
            }
        }
    }

    fun directionLabel(direction: String): String {
        return when (direction) {
            "in" -> if (AppLocaleText.isBulgarian()) "входящ" else "incoming"
            "out" -> if (AppLocaleText.isBulgarian()) "изходящ" else "outgoing"
            else -> if (AppLocaleText.isBulgarian()) "разговор" else "call"
        }
    }

    fun formatStartedAt(startedAt: Long): String {
        if (startedAt <= 0L) return ""
        val locale = if (AppLocaleText.isBulgarian()) Locale("bg") else Locale.US
        return SimpleDateFormat("dd.MM HH:mm", locale).format(Date(startedAt))
    }

    fun formatDuration(seconds: Long): String {
        val suffixSeconds = if (AppLocaleText.isBulgarian()) "сек" else "s"
        val suffixMinutes = if (AppLocaleText.isBulgarian()) "м" else "m"
        if (seconds <= 0L) return "0 $suffixSeconds"
        val minutes = seconds / 60
        val restSeconds = seconds % 60
        return if (minutes > 0) "$minutes$suffixMinutes $restSeconds$suffixSeconds" else "$restSeconds$suffixSeconds"
    }

    private fun directionFromType(type: Int): String {
        return when (type) {
            CallLog.Calls.INCOMING_TYPE,
            CallLog.Calls.MISSED_TYPE,
            CallLog.Calls.REJECTED_TYPE,
            CallLog.Calls.BLOCKED_TYPE -> "in"
            CallLog.Calls.OUTGOING_TYPE -> "out"
            else -> "in"
        }
    }

    private fun samePhone(normalizedPhone: String, candidate: String): Boolean {
        return PhoneNormalizer.samePhone(normalizedPhone, candidate)
    }

    private fun phoneCandidates(value: String): List<String> {
        return PhoneNormalizer.candidates(value)
    }

    private fun normalizePhone(phone: String): String {
        return PhoneNormalizer.key(phone)
    }
}
