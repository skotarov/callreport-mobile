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
        val pending = pendingState(phone, row, remoteEnabled, foreignRecord)
        val colors = colorsFor(row, readOnlyNote, pending.any)
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
            pending = pending,
        ).apply {
            background = roundedRect(colors.first, dp(12), colors.second, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    /** Notes attached to one exact call share one card and one pending state. */
    fun createGroup(
        phone: String,
        rows: List<CallReportHistoryRow>,
        onEditCallNote: (ContactCallNote) -> Unit,
        remoteEnabled: Boolean,
        companyNames: Map<String, String>,
    ): View {
        val first = rows.first()
        val pendingByRow = rows.associateWith { row ->
            pendingState(phone, row, remoteEnabled, foreignRecord = false)
        }
        val groupColors = if (pendingByRow.values.any { it.any }) NoteUiStyle.Pending else NoteUiStyle.Call
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(
                groupColors.background,
                dp(12),
                groupColors.border,
                dp(1),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(shared.metaView(first))
            rows.forEachIndexed { index, row ->
                val pending = pendingByRow.getValue(row)
                addView(
                    buildRowContent(
                        phone = phone,
                        row = row,
                        onEditCallNote = onEditCallNote,
                        remoteEnabled = remoteEnabled,
                        companyNames = companyNames,
                        foreignRecord = false,
                        readOnlyNote = false,
                        textColor = if (pending.any) NoteUiStyle.Pending.text else groupColors.text,
                        includeMeta = false,
                        pending = pending,
                    ).apply {
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
        pending: PendingState,
    ): LinearLayout = LinearLayout(activity).apply {
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

        if (includeMeta) addView(shared.metaView(row, muted = readOnlyNote || pending.any))
        shared.companyLabel(row.companyId, companyNames, muted = readOnlyNote || pending.any)?.let(::addView)
        if (row.text.isNotBlank()) addView(shared.noteText(row.text, textColor))
        when {
            pending.companyChoice -> addView(shared.pendingCompanyChoiceText())
            pending.companySync -> addView(
                shared.pendingSyncText(
                    if (pending.newCompanySync) "" else CallReportTopicNoteOutbox.lastFailure(activity),
                ),
            )
            pending.genericSync -> addView(
                shared.pendingSyncText(CallReportNoteOutbox.lastFailure(activity)),
            )
        }
        if (readOnlyNote) addView(shared.authorText(row.authorName.ifBlank { "друг потребител" }))
        if (!foreignRecord && remoteEnabled && row.serverNewer) addView(shared.serverNewerText())
    }

    private fun pendingState(
        phone: String,
        row: CallReportHistoryRow,
        remoteEnabled: Boolean,
        foreignRecord: Boolean,
    ): PendingState {
        if (foreignRecord || row.kind != CallReportHistoryRowKind.NOTE) return PendingState()
        val localNote = row.localNote
        val serverConfirmed = shared.isServerConfirmed(phone, row)
        val genericSync = remoteEnabled && !serverConfirmed &&
            localNote?.let { CallReportNoteOutbox.isCallPending(activity, phone, it) } == true
        val newCompanySync = remoteEnabled && CompanyCallNoteOutbox.isCallPending(
            activity,
            phone,
            localNote?.direction ?: row.direction,
            localNote?.callAt ?: row.timeMs,
        )
        val legacyCompanySync = remoteEnabled && localNote?.let {
            CallReportTopicNoteOutbox.isCallPending(activity, phone, it.direction, it.callAt)
        } == true
        val companyChoice = localNote?.let {
            CallReportDeferredCompanyAssignmentStore.isCallPending(
                activity,
                phone,
                it.direction,
                it.callAt,
            )
        } == true
        return PendingState(
            genericSync = genericSync,
            newCompanySync = newCompanySync,
            legacyCompanySync = legacyCompanySync,
            companyChoice = companyChoice,
        )
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
        pending: Boolean,
    ): Triple<Int, Int, Int> = when {
        readOnlyNote -> Triple(
            CallReportHistorySharedUi.FOREIGN_BACKGROUND,
            CallReportHistorySharedUi.FOREIGN_BORDER,
            CallReportHistorySharedUi.FOREIGN_TEXT,
        )
        pending -> Triple(
            NoteUiStyle.Pending.background,
            NoteUiStyle.Pending.border,
            NoteUiStyle.Pending.text,
        )
        row.kind == CallReportHistoryRowKind.NOTE -> Triple(
            NoteUiStyle.Call.background,
            NoteUiStyle.Call.border,
            NoteUiStyle.Call.text,
        )
        else -> Triple(Color.WHITE, Color.rgb(226, 232, 240), Color.rgb(30, 41, 59))
    }

    private data class PendingState(
        val genericSync: Boolean = false,
        val newCompanySync: Boolean = false,
        val legacyCompanySync: Boolean = false,
        val companyChoice: Boolean = false,
    ) {
        val companySync: Boolean get() = newCompanySync || legacyCompanySync
        val any: Boolean get() = genericSync || companySync || companyChoice
    }
}
