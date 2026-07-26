package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView

/** Reusable metadata, author and server-version indicators for full-history rows. */
internal class FilteredFullLogMetadataUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun addServerAuthor(container: LinearLayout, row: CallReportHistoryRow) {
        if (!row.authorIsOtherBroker || row.authorName.isBlank()) return
        container.addView(TextView(activity).apply {
            text = "Записал: ${row.authorName}"
            textSize = 12f
            setTextColor(FilteredFullLogStyle.foreignText)
            setPadding(0, dp(6), 0, 0)
        })
    }

    fun addServerVersionNotice(container: LinearLayout, row: CallReportHistoryRow) {
        if (!row.serverNewer || row.authorIsOtherBroker) return
        container.addView(TextView(activity).apply {
            text = "По-нова версия на бележката е на сървъра"
            textSize = 12f
            setTextColor(Color.rgb(37, 99, 235))
            setPadding(0, dp(6), 0, 0)
        })
    }

    fun metaView(row: CallReportHistoryRow, remoteEnabled: Boolean): TextView {
        val type = when (row.kind) {
            CallReportHistoryRowKind.PHONE -> ""
            CallReportHistoryRowKind.SMS -> "SMS"
            CallReportHistoryRowKind.NOTE -> "Бележка"
        }
        val direction = when (row.direction) {
            "in" -> "входящ"
            "out" -> "изходящ"
            else -> ""
        }
        val duration = if (row.kind == CallReportHistoryRowKind.PHONE) {
            PhoneCallReader.formatDuration(row.durationSeconds)
        } else ""
        return TextView(activity).apply {
            text = listOf(type, PhoneCallReader.formatStartedAt(row.timeMs), direction, duration)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            textSize = 12.5f
            setTextColor(if (row.authorIsOtherBroker) FilteredFullLogStyle.foreignText else Color.rgb(71, 85, 105))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val leftIcon = if (row.kind == CallReportHistoryRowKind.PHONE) callStatusIcon(row) else 0
            val rightIcon = if (
                remoteEnabled && row.hasServerCopy && row.kind != CallReportHistoryRowKind.NOTE
            ) R.drawable.ic_cloud_note else 0
            if (leftIcon != 0 || rightIcon != 0) {
                setCompoundDrawablesWithIntrinsicBounds(leftIcon, 0, rightIcon, 0)
                compoundDrawablePadding = dp(6)
            }
        }
    }

    private fun callStatusIcon(row: CallReportHistoryRow): Int = when {
        row.status == "rejected" || row.status == "blocked" -> R.drawable.ic_call_rejected
        row.status == "missed" -> R.drawable.ic_call_missed
        row.direction == "out" && row.durationSeconds <= 0L -> R.drawable.ic_call_rejected
        row.direction != "out" && row.durationSeconds <= 0L -> R.drawable.ic_call_missed
        row.direction == "out" -> R.drawable.ic_call_outgoing
        else -> R.drawable.ic_call_incoming
    }
}

internal object FilteredFullLogStyle {
    val foreignBackground: Int = Color.rgb(241, 245, 249)
    val foreignBorder: Int = Color.rgb(203, 213, 225)
    val foreignText: Int = Color.rgb(100, 116, 139)
}
