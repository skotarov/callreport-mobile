package com.onlineimoti.calllog

import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs

/** Renders one prepared Notes/SMS page, including week headings and same-call note groups. */
internal class CallReportHistoryPageRowsUi(
    private val dp: (Int) -> Int,
    private val weekUi: CallReportHistoryWeekUi,
    private val noteRowUi: CallReportHistoryNoteRowUi,
    private val smsRowUi: CallReportHistorySmsRowUi,
) {
    fun render(
        root: LinearLayout,
        phone: String,
        rows: List<CallReportHistoryRow>,
        remoteEnabled: Boolean,
        companyNames: Map<String, String>,
        onEditCallNote: (ContactCallNote) -> Unit,
        onEditSms: (SmsMessageRecord, String) -> Unit,
        previousRow: CallReportHistoryRow? = null,
    ) {
        val currentWeekSerial = weekUi.currentWeekSerial()
        var previousWeekSerial = previousRow?.let { weekUi.weekStartSerial(it.timeMs) }
        var index = 0
        while (index < rows.size) {
            val row = rows[index]
            val rowWeekSerial = weekUi.weekStartSerial(row.timeMs)
            if (rowWeekSerial != null && rowWeekSerial != previousWeekSerial) {
                val relativeWeeks = currentWeekSerial
                    ?.let { (it - rowWeekSerial) / CallReportHistoryWeekUi.DAYS_PER_WEEK }
                    ?: 0L
                root.addView(weekUi.separator(row.timeMs, relativeWeeks))
                previousWeekSerial = rowWeekSerial
            }
            val grouped = if (canGroup(row)) collectSameCallNotes(rows, index) else emptyList()
            val item = if (grouped.size > 1) {
                noteRowUi.createGroup(phone, grouped, onEditCallNote, remoteEnabled, companyNames)
            } else {
                rowView(phone, row, onEditCallNote, onEditSms, remoteEnabled, companyNames)
            }
            root.addView(ListThemeUi.applyRowSpacing(item, dp))
            index += grouped.size.takeIf { it > 1 } ?: 1
        }
    }

    private fun collectSameCallNotes(rows: List<CallReportHistoryRow>, startIndex: Int): List<CallReportHistoryRow> {
        val first = rows[startIndex]
        val grouped = mutableListOf(first)
        var index = startIndex + 1
        while (index < rows.size && sameCall(first, rows[index])) grouped += rows[index++]
        return grouped
    }

    private fun canGroup(row: CallReportHistoryRow): Boolean =
        row.kind == CallReportHistoryRowKind.NOTE && row.editable && !row.authorIsOtherBroker

    private fun sameCall(first: CallReportHistoryRow, second: CallReportHistoryRow): Boolean {
        if (!canGroup(first) || !canGroup(second)) return false
        val firstTime = callIdentityTime(first)
        val secondTime = callIdentityTime(second)
        if (firstTime <= 0L || secondTime <= 0L || abs(firstTime - secondTime) > SAME_CALL_TIME_TOLERANCE_MS) {
            return false
        }
        return first.direction.isBlank() || second.direction.isBlank() || first.direction == second.direction
    }

    private fun callIdentityTime(row: CallReportHistoryRow): Long =
        row.localNote?.callAt?.takeIf { it > 0L }
            ?: row.serverEvent?.occurredAtMs?.takeIf { it > 0L }
            ?: row.timeMs

    private fun rowView(
        phone: String,
        row: CallReportHistoryRow,
        onEditCallNote: (ContactCallNote) -> Unit,
        onEditSms: (SmsMessageRecord, String) -> Unit,
        remoteEnabled: Boolean,
        companyNames: Map<String, String>,
    ): View = if (row.kind == CallReportHistoryRowKind.SMS) {
        smsRowUi.create(row, onEditSms, remoteEnabled, companyNames)
    } else {
        noteRowUi.create(phone, row, onEditCallNote, remoteEnabled, companyNames)
    }

    private companion object {
        const val SAME_CALL_TIME_TOLERANCE_MS = 2_000L
    }
}
