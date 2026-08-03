package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.view.View

internal class CallReportHistorySmsRowUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val shared: CallReportHistorySharedUi,
) {
    /** Uses the same icon, metadata, contact title and body layout as the Call Log SMS cards. */
    fun create(
        row: CallReportHistoryRow,
        onEditSms: (SmsMessageRecord, String) -> Unit,
        remoteEnabled: Boolean,
        companyNames: Map<String, String>,
    ): View {
        val foreignRecord = remoteEnabled && row.authorIsOtherBroker
        val displayName = row.serverEvent?.contactName.orEmpty().trim()
            .ifBlank { ContactGroupFilter.resolveDisplayName(activity, row.phone).orEmpty() }
        val message = PhoneCallRecord(
            number = row.phone,
            name = displayName,
            direction = if (row.direction == "out" || row.direction == "sms_out") {
                "sms_out"
            } else {
                "sms_in"
            },
            startedAt = row.timeMs,
            durationSeconds = 0L,
            smsBody = row.text,
            providerId = row.localSms?.providerId.orEmpty(),
        )
        val colors = SmsTimelineCard.Colors(
            background = if (foreignRecord) {
                CallReportHistorySharedUi.FOREIGN_BACKGROUND
            } else {
                CallReportHistorySharedUi.SMS_BACKGROUND
            },
            border = if (foreignRecord) {
                CallReportHistorySharedUi.FOREIGN_BORDER
            } else {
                Color.rgb(226, 232, 240)
            },
            title = if (foreignRecord) {
                CallReportHistorySharedUi.FOREIGN_TEXT
            } else {
                Color.rgb(30, 41, 59)
            },
            meta = if (foreignRecord) {
                CallReportHistorySharedUi.FOREIGN_TEXT
            } else {
                Color.rgb(71, 85, 105)
            },
            body = if (foreignRecord) {
                CallReportHistorySharedUi.FOREIGN_TEXT
            } else {
                Color.rgb(30, 41, 59)
            },
        )
        val editableSms = row.localSms?.takeIf { !foreignRecord }
        return SmsTimelineCard.create(
            activity = activity,
            dp = dp,
            message = message,
            displayName = message.displayName,
            colors = colors,
            metaTrailingIconRes = if (remoteEnabled && row.hasServerCopy) {
                R.drawable.ic_cloud_note
            } else {
                0
            },
            beforeBody = { column ->
                shared.companyLabel(
                    row.companyId,
                    companyNames,
                    muted = foreignRecord,
                )?.let(column::addView)
            },
            afterBody = { column ->
                if (foreignRecord) {
                    column.addView(
                        shared.authorText(row.authorName.ifBlank { "друг потребител" }),
                    )
                }
                if (!foreignRecord && remoteEnabled && row.serverNewer) {
                    column.addView(shared.serverNewerText())
                }
            },
            onClick = {
                SmsMessageViewDialog(activity, dp).show(
                    phone = row.phone,
                    title = message.displayName.toString().trim().ifBlank { row.phone },
                    body = row.text,
                    receivedAtMs = row.timeMs,
                    direction = message.direction,
                    showReplyAction = SmsMessageDetailPolicy.canReplyTo(row.phone),
                    onEdit = editableSms?.let { sms ->
                        { onEditSms(sms, row.companyId) }
                    },
                )
            },
        )
    }
}
