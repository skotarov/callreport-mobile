package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

internal class CallReportHistorySharedUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun companyLabel(
        companyId: String,
        companyNames: Map<String, String>,
        muted: Boolean = false,
    ): TextView? {
        val id = companyId.trim()
        if (id.isBlank()) return null
        val name = historyCategoryName(companyNames[id].orEmpty().ifBlank { id })
        return TextView(activity).apply {
            text = name
            textSize = 11.5f
            setTextColor(if (muted) FOREIGN_TEXT else Color.rgb(71, 85, 105))
            setPadding(dp(7), dp(3), dp(7), dp(3))
            activity.getDrawable(R.drawable.ic_cloud_note)?.apply {
                setBounds(0, 0, dp(14), dp(14))
                setCompoundDrawables(this, null, null, null)
                compoundDrawablePadding = dp(4)
            }
            background = roundedRect(
                if (muted) FOREIGN_BADGE_BACKGROUND else Color.rgb(241, 245, 249),
                dp(8),
                Color.TRANSPARENT,
                0,
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
    }

    /**
     * History presents the destination as a note category. Keep the CRM filter wording
     * "Без фирма" unchanged elsewhere, but use the clearer History-only label here.
     */
    private fun historyCategoryName(value: String): String {
        val name = value.trim()
        if (!name.equals("Без фирма", ignoreCase = true)) return name
        return if (AppLocaleText.isBulgarian()) "Без категория" else "Uncategorized"
    }

    fun noteText(value: String, color: Int): TextView = TextView(activity).apply {
        text = value
        textSize = 14.5f
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(5), 0, 0)
    }

    fun pendingCompanyChoiceText(): TextView = TextView(activity).apply {
        text = activity.getString(R.string.dynamic_note_pending_company_choice)
        textSize = 12f
        setTextColor(Color.rgb(146, 64, 14))
        setPadding(0, dp(6), 0, 0)
    }

    fun pendingSyncText(failure: String): TextView = TextView(activity).apply {
        text = if (failure.isBlank()) {
            activity.getString(R.string.dynamic_note_pending_server_sync)
        } else {
            activity.getString(R.string.dynamic_note_pending_server_sync_failed, failure)
        }
        textSize = 12f
        setTextColor(if (failure.isBlank()) Color.rgb(100, 116, 139) else Color.rgb(185, 28, 28))
        setPadding(0, dp(6), 0, 0)
    }

    fun serverNewerText(): TextView = TextView(activity).apply {
        text = "По-нова версия на сървъра"
        textSize = 12f
        setTextColor(Color.rgb(37, 99, 235))
        setPadding(0, dp(6), 0, 0)
    }

    fun authorText(authorName: String): TextView = TextView(activity).apply {
        text = "Записал: $authorName"
        textSize = 12f
        setTextColor(FOREIGN_TEXT)
        setPadding(0, dp(6), 0, 0)
    }

    fun metaView(row: CallReportHistoryRow, muted: Boolean = false): TextView {
        val kindText = when (row.kind) {
            CallReportHistoryRowKind.SMS -> "SMS"
            CallReportHistoryRowKind.NOTE,
            CallReportHistoryRowKind.PHONE -> ""
        }
        return TextView(activity).apply {
            text = listOf(kindText, PhoneCallReader.formatStartedAt(row.timeMs), directionLabel(row.direction))
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            textSize = 12.5f
            setTextColor(if (muted || row.authorIsOtherBroker) FOREIGN_TEXT else Color.rgb(71, 85, 105))
        }
    }

    fun isServerConfirmed(phone: String, row: CallReportHistoryRow): Boolean = when (row.kind) {
        CallReportHistoryRowKind.SMS -> row.hasServerCopy || row.localSms?.providerId
            ?.takeIf { it.isNotBlank() }
            ?.let {
                ServerRecordIndex.isConfirmed(
                    activity,
                    ServerRecordIndex.communicationEventId(activity, "sms", it),
                )
            } == true
        CallReportHistoryRowKind.NOTE -> row.hasServerCopy || row.localNote?.let {
            ServerRecordIndex.isCallNoteConfirmed(activity, phone, it)
        } == true
        CallReportHistoryRowKind.PHONE -> false
    }

    fun directionLabel(direction: String): String = when (direction) {
        "in" -> "входящ"
        "out" -> "изходящ"
        else -> ""
    }

    companion object {
        val SMS_BACKGROUND: Int = Color.rgb(248, 250, 252)
        val FOREIGN_BACKGROUND: Int = Color.rgb(241, 245, 249)
        val FOREIGN_BORDER: Int = Color.rgb(203, 213, 225)
        val FOREIGN_TEXT: Int = Color.rgb(100, 116, 139)
        val FOREIGN_BADGE_BACKGROUND: Int = Color.rgb(226, 232, 240)
    }
}
