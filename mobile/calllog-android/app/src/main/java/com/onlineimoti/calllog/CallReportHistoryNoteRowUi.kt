package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout

internal class CallReportHistoryNoteRowUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val shared: CallReportHistorySharedUi,
) {
    fun create(
        phone: String,
        row: CallReportHistoryRow,
        onEditCallNote: (ContactCallNote) -> Unit,
        remoteEnabled: Boolean,
        companyNames: Map<String, String>,
    ): View {
        val foreignRecord = remoteEnabled && row.authorIsOtherBroker
        val readOnlyNote = row.kind == CallReportHistoryRowKind.NOTE && !row.editable
        val colors = colorsFor(row, readOnlyNote)
        return buildRowContent(
            phone = phone,
            row = row,
            onEditCallNote = onEditCallNote,
            remoteEnabled = remoteEnabled,
            companyNames = companyNames,
            foreignRecord = foreignRecord,
            readOnlyNote = readOnlyNote,
            textColor = colors.third,
            includeMeta = true,
        ).apply {
            background = roundedRect(colors.first, dp(12), colors.second, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    /**
     * Notes attached to one exact call share one blue card. The call metadata is
     * shown once; company badges and note texts separate the individual notes
     * without borders or divider lines that would make them look like new calls.
     */
    fun createGroup(
        phone: String,
        rows: List<CallReportHistoryRow>,
        onEditCallNote: (ContactCallNote) -> Unit,
        remoteEnabled: Boolean,
        companyNames: Map<String, String>,
    ): View {
        val first = rows.first()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(
                NoteUiStyle.Call.background,
                dp(12),
                NoteUiStyle.Call.border,
                dp(1),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(shared.metaView(first))
            rows.forEachIndexed { index, row ->
                addView(
                    buildRowContent(
                        phone = phone,
                        row = row,
                        onEditCallNote = onEditCallNote,
                        remoteEnabled = remoteEnabled,
                        companyNames = companyNames,
                        foreignRecord = false,
                        readOnlyNote = false,
                        textColor = NoteUiStyle.Call.text,
                        includeMeta = false,
                    ).apply {
                        // A little breathing room is enough; intentionally no line.
                        setPadding(0, if (index == 0) 0 else dp(2), 0, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    },
                )
            }
        }
    }

    private fun buildRowContent(
        phone: String,
        row: CallReportHistoryRow,
        onEditCallNote: (ContactCallNote) -> Unit,
        remoteEnabled: Boolean,
        companyNames: Map<String, String>,
        foreignRecord: Boolean,
        readOnlyNote: Boolean,
        textColor: Int,
        includeMeta: Boolean,
    ): LinearLayout {
        val serverConfirmed = shared.isServerConfirmed(phone, row)
        val localNote = row.localNote
        val pendingGenericSync =
            !foreignRecord && remoteEnabled && row.kind == CallReportHistoryRowKind.NOTE &&
                localNote?.let { CallReportNoteOutbox.isCallPending(activity, phone, it) } == true
        val pendingNewCompanySync =
            !foreignRecord && remoteEnabled && row.kind == CallReportHistoryRowKind.NOTE &&
                CompanyCallNoteOutbox.isCallPending(
                    activity,
                    phone,
                    localNote?.direction ?: row.direction,
                    localNote?.callAt ?: row.timeMs,
                )
        val pendingLegacyCompanySync =
            !foreignRecord && remoteEnabled && row.kind == CallReportHistoryRowKind.NOTE &&
                localNote?.let {
                    CallReportTopicNoteOutbox.isCallPending(activity, phone, it.direction, it.callAt)
                } == true
        val pendingCompanySync = pendingNewCompanySync || pendingLegacyCompanySync
        val pendingCompanyChoice =
            !foreignRecord && row.kind == CallReportHistoryRowKind.NOTE &&
                localNote?.let {
                    CallReportDeferredCompanyAssignmentStore.isCallPending(
                        activity, phone, it.direction, it.callAt,
                    )
                } == true

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (includeMeta) setPadding(dp(12), dp(10), dp(12), dp(10))
            if (readOnlyNote) {
                val author = row.authorName.ifBlank { "друг потребител" }
                contentDescription = "Неактивна бележка. Записал: $author."
                isClickable = false
                isFocusable = false
                isLongClickable = false
                setOnClickListener(null)
            } else if (row.kind == CallReportHistoryRowKind.NOTE && row.editable) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onEditCallNote(editableNote(phone, row, remoteEnabled))
                }
            }

            if (includeMeta) addView(shared.metaView(row, muted = readOnlyNote))
            shared.companyLabel(row.companyId, companyNames, muted = readOnlyNote)?.let(::addView)
            if (row.text.isNotBlank()) addView(shared.noteText(row.text, textColor))
            when {
                pendingCompanyChoice -> addView(shared.pendingCompanyChoiceText())
                pendingCompanySync -> addView(
                    shared.pendingSyncText(
                        if (pendingNewCompanySync) "" else CallReportTopicNoteOutbox.lastFailure(activity),
                    ),
                )
                pendingGenericSync && !serverConfirmed -> addView(
                    shared.pendingSyncText(CallReportNoteOutbox.lastFailure(activity)),
                )
            }
            if (readOnlyNote) addView(shared.authorText(row.authorName.ifBlank { "друг потребител" }))
            if (!foreignRecord && remoteEnabled && row.serverNewer) addView(shared.serverNewerText())
        }
    }

    private fun editableNote(
        phone: String,
        row: CallReportHistoryRow,
        remoteEnabled: Boolean,
    ): ContactCallNote {
        val source = row.localNote?.let { existingLocalNote ->
            val serverClientEventId = row.serverEvent?.clientEventId.orEmpty()
            if (serverClientEventId.isBlank() || existingLocalNote.serverClientEventId == serverClientEventId) {
                existingLocalNote
            } else {
                existingLocalNote.copy(serverClientEventId = serverClientEventId)
            }
        } ?: ContactCallNote(
            note = row.text,
            callAt = row.timeMs,
            savedAt = row.serverEvent?.updatedAtMs ?: row.timeMs,
            direction = row.direction,
            durationSeconds = row.durationSeconds,
            clientNoteId = LocalNotesFileStore.clientNoteIdForCall(phone, row.timeMs, row.direction),
            companyId = row.companyId,
            serverClientEventId = row.serverEvent?.clientEventId.orEmpty(),
        )
        return if (remoteEnabled && row.serverNewer) {
            source.copy(
                note = row.text,
                savedAt = maxOf(source.savedAt, row.serverEvent?.updatedAtMs ?: 0L),
                companyId = row.companyId.ifBlank { source.companyId },
                serverClientEventId = row.serverEvent?.clientEventId.orEmpty()
                    .ifBlank { source.serverClientEventId },
            )
        } else {
            source.copy(
                companyId = row.companyId.ifBlank { source.companyId },
                serverClientEventId = row.serverEvent?.clientEventId.orEmpty()
                    .ifBlank { source.serverClientEventId },
            )
        }
    }

    private fun colorsFor(
        row: CallReportHistoryRow,
        readOnlyNote: Boolean,
    ): Triple<Int, Int, Int> = when {
        readOnlyNote -> Triple(
            CallReportHistorySharedUi.FOREIGN_BACKGROUND,
            CallReportHistorySharedUi.FOREIGN_BORDER,
            CallReportHistorySharedUi.FOREIGN_TEXT,
        )
        row.kind == CallReportHistoryRowKind.NOTE -> Triple(
            NoteUiStyle.Call.background,
            NoteUiStyle.Call.border,
            NoteUiStyle.Call.text,
        )
        else -> Triple(Color.WHITE, Color.rgb(226, 232, 240), Color.rgb(30, 41, 59))
    }
}
