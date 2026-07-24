package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.CallLog
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Renders already prepared Notes and SMS directly on the History background. */
internal class CallReportHistoryRowsUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    private val paginationUi by lazy { CallReportHistoryPaginationUi(activity, dp, roundedRect) }
    private val sharedUi by lazy { CallReportHistorySharedUi(activity, dp, roundedRect) }
    private val weekUi by lazy { CallReportHistoryWeekUi(activity, dp) }
    private val noteRowUi by lazy { CallReportHistoryNoteRowUi(activity, dp, roundedRect, sharedUi) }
    private val smsRowUi by lazy { CallReportHistorySmsRowUi(activity, dp, sharedUi) }

    fun canPreviousPage(): Boolean = paginationUi.canPrevious()
    fun canNextPage(): Boolean = paginationUi.canNext()
    fun previousPage(onPageChanged: () -> Unit): Boolean = paginationUi.previousPage(onPageChanged)
    fun nextPage(onPageChanged: () -> Unit): Boolean = paginationUi.nextPage(onPageChanged)
    fun resetPage() = paginationUi.reset()

    fun addSection(
        root: LinearLayout,
        phone: String,
        remoteEnabled: Boolean,
        principal: CallReportHistoryPrincipal,
        rows: List<CallReportHistoryRow>,
        latestLocalCall: PhoneCallRecord?,
        localNotes: List<ContactCallNote>,
        localLoading: Boolean,
        serverLoading: Boolean,
        onEditCallNote: (ContactCallNote) -> Unit,
        onEditSms: (SmsMessageRecord, String) -> Unit,
        onPageChanged: () -> Unit,
    ) {
        val companyNames = principal.companies.associate { it.id to it.name }
        val page = paginationUi.currentPage(rows)

        root.addView(titleRow())
        latestCallWithoutNote(latestLocalCall, localNotes)?.let { call ->
            val latestRow = addLatestCallNoteCard(call) { onEditCallNote(call.toContactCallNote()) }
            root.addView(ListThemeUi.applyRowSpacing(latestRow, dp))
        }
        val currentWeekSerial = weekUi.currentWeekSerial()
        var previousWeekSerial: Long? = null
        var index = 0
        while (index < page.rows.size) {
            val row = page.rows[index]
            val rowWeekSerial = weekUi.weekStartSerial(row.timeMs)
            if (rowWeekSerial != null && rowWeekSerial != previousWeekSerial) {
                val relativeWeeks = currentWeekSerial
                    ?.let { (it - rowWeekSerial) / CallReportHistoryWeekUi.DAYS_PER_WEEK }
                    ?: 0L
                root.addView(weekUi.separator(row.timeMs, relativeWeeks))
                previousWeekSerial = rowWeekSerial
            }

            val groupedNotes = if (canGroupNote(row)) {
                collectSameCallNotes(page.rows, index)
            } else {
                emptyList()
            }
            val item = if (groupedNotes.size > 1) {
                noteRowUi.createGroup(
                    phone = phone,
                    rows = groupedNotes,
                    onEditCallNote = onEditCallNote,
                    remoteEnabled = remoteEnabled,
                    companyNames = companyNames,
                )
            } else {
                historyRow(
                    phone,
                    row,
                    onEditCallNote,
                    onEditSms,
                    remoteEnabled,
                    companyNames,
                )
            }
            root.addView(ListThemeUi.applyRowSpacing(item, dp))
            index += groupedNotes.size.takeIf { it > 1 } ?: 1
        }
        paginationUi.addNavigation(root, page, onPageChanged)
        addEmptyState(
            container = root,
            rows = rows,
            latestCall = latestLocalCall,
            localNotes = localNotes,
            localLoading = localLoading,
            serverLoading = serverLoading,
            remoteEnabled = remoteEnabled,
        )
    }

    private fun collectSameCallNotes(
        rows: List<CallReportHistoryRow>,
        startIndex: Int,
    ): List<CallReportHistoryRow> {
        val first = rows[startIndex]
        val grouped = mutableListOf(first)
        var index = startIndex + 1
        while (index < rows.size) {
            val candidate = rows[index]
            if (!sameCall(first, candidate)) break
            grouped += candidate
            index += 1
        }
        return grouped
    }

    private fun canGroupNote(row: CallReportHistoryRow): Boolean =
        row.kind == CallReportHistoryRowKind.NOTE && row.editable && !row.authorIsOtherBroker

    private fun sameCall(
        first: CallReportHistoryRow,
        second: CallReportHistoryRow,
    ): Boolean {
        if (!canGroupNote(first) || !canGroupNote(second)) return false
        val firstTime = callIdentityTime(first)
        val secondTime = callIdentityTime(second)
        if (firstTime <= 0L || secondTime <= 0L) return false
        if (abs(firstTime - secondTime) > SAME_CALL_TIME_TOLERANCE_MS) return false
        return first.direction.isBlank() || second.direction.isBlank() || first.direction == second.direction
    }

    private fun callIdentityTime(row: CallReportHistoryRow): Long =
        row.localNote?.callAt?.takeIf { it > 0L }
            ?: row.serverEvent?.occurredAtMs?.takeIf { it > 0L }
            ?: row.timeMs

    private fun latestCallWithoutNote(
        latestCall: PhoneCallRecord?,
        localNotes: List<ContactCallNote>,
    ): PhoneCallRecord? {
        val call = latestCall ?: return null
        val alreadyHasNote = localNotes.any { note ->
            note.callAt == call.startedAt &&
                (note.direction.isBlank() || call.direction.isBlank() || note.direction == call.direction)
        }
        return call.takeUnless { alreadyHasNote }
    }

    private fun titleRow(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(8))
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_system_call_log)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) }
        })
        addView(TextView(activity).apply {
            text = "Бележки и SMS"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(30, 41, 59))
        })
    }

    private fun addLatestCallNoteCard(call: PhoneCallRecord, action: () -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            addView(ImageView(activity).apply {
                setImageResource(callStatusIcon(call))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(3), dp(3), dp(3), dp(3))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(18)).apply {
                    marginEnd = dp(4)
                }
            })
            addView(TextView(activity).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(call.startedAt),
                    sharedUi.directionLabel(call.direction),
                    PhoneCallReader.formatDuration(call.durationSeconds),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(Color.rgb(100, 116, 139))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            })
            addView(TextView(activity).apply {
                text = activity.getString(R.string.dynamic_notes_add_general)
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(NoteUiStyle.General.mutedText)
                setPadding(dp(12), 0, 0, 0)
            })
        }
    }

    private fun callStatusIcon(call: PhoneCallRecord): Int = when (call.callType) {
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> R.drawable.ic_call_missed
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> R.drawable.ic_call_rejected
        CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_outgoing
        CallLog.Calls.INCOMING_TYPE -> R.drawable.ic_call_incoming
        else -> when {
            call.direction == "out" && call.durationSeconds <= 0L -> R.drawable.ic_call_rejected
            call.direction != "out" && call.durationSeconds <= 0L -> R.drawable.ic_call_missed
            call.direction == "out" -> R.drawable.ic_call_outgoing
            else -> R.drawable.ic_call_incoming
        }
    }

    private fun historyRow(
        phone: String,
        row: CallReportHistoryRow,
        onEditCallNote: (ContactCallNote) -> Unit,
        onEditSms: (SmsMessageRecord, String) -> Unit,
        remoteEnabled: Boolean,
        companyNames: Map<String, String>,
    ): View {
        return if (row.kind == CallReportHistoryRowKind.SMS) {
            smsRowUi.create(row, onEditSms, remoteEnabled, companyNames)
        } else {
            noteRowUi.create(phone, row, onEditCallNote, remoteEnabled, companyNames)
        }
    }

    private fun addEmptyState(
        container: LinearLayout,
        rows: List<CallReportHistoryRow>,
        latestCall: PhoneCallRecord?,
        localNotes: List<ContactCallNote>,
        localLoading: Boolean,
        serverLoading: Boolean,
        remoteEnabled: Boolean,
    ) {
        if (localLoading || (remoteEnabled && serverLoading)) return
        if (rows.isEmpty() && latestCallWithoutNote(latestCall, localNotes) == null) {
            container.addView(status("Няма SMS или бележки за този номер"))
        }
    }

    private fun status(textValue: String): TextView = TextView(activity).apply {
        text = textValue
        textSize = 13f
        setTextColor(Color.rgb(100, 116, 139))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun PhoneCallRecord.toContactCallNote(): ContactCallNote = ContactCallNote(
        note = "",
        callAt = startedAt,
        savedAt = startedAt,
        direction = direction,
        durationSeconds = durationSeconds,
        clientNoteId = LocalNotesFileStore.clientNoteIdForCall(number, startedAt, direction),
    )

    private companion object {
        const val SAME_CALL_TIME_TOLERANCE_MS = 2_000L
    }
}
