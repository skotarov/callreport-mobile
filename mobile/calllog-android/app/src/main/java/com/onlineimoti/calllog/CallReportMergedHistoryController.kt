package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

/** Public History facade; loading and mutable snapshots live in focused collaborators. */
internal class CallReportMergedHistoryController(
    private val activity: Activity,
    @Suppress("unused") private val headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val rerender: () -> Unit,
) {
    private val rowsUi by lazy { CallReportHistoryRowsUi(activity, dp, roundedRect) }
    private val fullLogUi by lazy { ContactNotesFullLogUi(activity, dp, roundedRect) }
    private val loader by lazy {
        CallReportMergedHistoryLoader(
            activity = activity,
            rerender = rerender,
            resetPages = {
                rowsUi.resetPage()
                fullLogUi.resetPage()
            },
        )
    }

    fun loadOnce(phone: String) = loader.loadOnce(phone)
    fun refreshServer(phone: String) = loader.refreshServer(phone)
    fun refreshLocal(phone: String) = loader.refreshLocal(phone)
    fun isLoading(): Boolean = loader.isLoading()

    fun canPreviousNotesPage(): Boolean = rowsUi.canPreviousPage()
    fun canNextNotesPage(): Boolean = rowsUi.canNextPage()
    fun previousNotesPage(): Boolean = rowsUi.previousPage(rerender)
    fun nextNotesPage(): Boolean = rowsUi.nextPage(rerender)
    fun resetNotesPage() = rowsUi.resetPage()

    fun canPreviousFullLogPage(): Boolean = fullLogUi.canPreviousPage()
    fun canNextFullLogPage(): Boolean = fullLogUi.canNextPage()
    fun previousFullLogPage(): Boolean = fullLogUi.previousPage(rerender)
    fun nextFullLogPage(): Boolean = fullLogUi.nextPage(rerender)
    fun resetFullLogPage() = fullLogUi.resetPage()

    fun contactExists(): Boolean = loader.state.contactExists
    fun localGeneralNote(): String = loader.state.localGeneralNote
    fun localGeneralNotePending(): Boolean = loader.state.localGeneralNotePending
    fun companyScopeAvailable(): Boolean = loader.state.companyScopeAvailable
    fun hasConfirmedLocalServerNote(): Boolean = loader.state.prepared.confirmedLocalServerNote
    fun hasCompanyMainNoteScope(): Boolean = loader.state.prepared.hasCompanyMainNoteScope
    fun hasServerRecordsFor(phone: String): Boolean = loader.hasServerRecordsFor(phone)

    /** Loading progress is shown only through the non-blocking black tooltip. */
    fun serverLoadingStatusText(): String = ""

    fun companyMainNotes(phone: String): List<CallReportCompanyMainNote> =
        loader.state.prepared.companyMainNotes.takeIf { phone == loader.state.activePhone }.orEmpty()

    fun unscopedServerMainNote(phone: String): CallReportHistoryEvent? =
        loader.state.prepared.unscopedServerMainNote.takeIf { phone == loader.state.activePhone }

    fun addNotesSection(
        root: LinearLayout,
        phone: String,
        onEditCallNote: (ContactCallNote) -> Unit,
        onEditSms: (SmsMessageRecord, String) -> Unit,
    ) {
        val state = loader.state
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        addServerErrorBelowContactName(root, remoteEnabled, state.loadError)
        rowsUi.addSection(
            root = root,
            phone = phone,
            remoteEnabled = remoteEnabled,
            principal = state.serverHistory.principal,
            rows = state.prepared.rows,
            latestLocalCall = state.latestLocalCall,
            localNotes = state.localNotes,
            localLoading = loader.isLocalPreparing(),
            serverLoading = loader.isServerLoading(),
            onEditCallNote = onEditCallNote,
            onEditSms = onEditSms,
            onPageChanged = rerender,
        )
    }

    fun addFullLogSection(
        root: LinearLayout,
        phone: String,
        openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    ) {
        val state = loader.state
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        addServerErrorBelowContactName(root, remoteEnabled, state.loadError)
        fullLogUi.addSection(
            root = root,
            phone = phone,
            incomingEntries = state.prepared.fullLogEntries,
            remoteEnabled = remoteEnabled,
            loading = loader.isLoading(),
            errorText = state.loadError,
            openCallNoteEditor = openCallNoteEditor,
        )
    }

    fun markRendered() = loader.markRendered()
    fun forceNextRenderAfterDataReady() = loader.forceNextRenderAfterDataReady()
    fun release() = loader.release()

    private fun addServerErrorBelowContactName(root: LinearLayout, remoteEnabled: Boolean, errorText: String) {
        if (!remoteEnabled || errorText.isBlank()) return
        root.addView(TextView(activity).apply {
            text = errorText
            textSize = 12.5f
            setTextColor(Color.rgb(185, 28, 28))
            setPadding(dp(2), 0, dp(2), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }, minOf(1, root.childCount))
    }
}
