package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import java.util.concurrent.Executors

/** Loads and prepares local and remote History data away from the main thread. */
internal class CallReportMergedHistoryController(
    private val activity: Activity,
    @Suppress("unused") private val headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    private val roundedRect: (
        color: Int,
        radius: Int,
        strokeColor: Int,
        strokeWidth: Int,
    ) -> GradientDrawable,
    private val rerender: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val loadExecutor = Executors.newFixedThreadPool(2)
    private val prepareExecutor = Executors.newSingleThreadExecutor()
    private val rowsUi by lazy { CallReportHistoryRowsUi(activity, dp, roundedRect) }
    private val fullLogUi by lazy { ContactNotesFullLogUi(activity, dp, roundedRect) }
    private val state = CallReportHistoryState()
    private val busyTracker = CallReportHistoryBusyTracker(activity)
    private val errorUi = CallReportHistoryErrorUi(activity, dp)
    private val prepareCoordinator = CallReportHistoryPrepareCoordinator(
        activity = activity,
        state = state,
        handler = handler,
        executor = prepareExecutor,
        busyTracker = busyTracker,
        rerender = rerender,
    )
    private val dataLoader = CallReportHistoryDataLoader(
        activity = activity,
        state = state,
        handler = handler,
        executor = loadExecutor,
        busyTracker = busyTracker,
        prepareCoordinator = prepareCoordinator,
    )

    fun loadOnce(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (state.started) return
        state.started = true
        dataLoader.refreshLocal(phone)
        dataLoader.refreshServer(phone)
    }

    fun refreshServer(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        dataLoader.refreshServer(phone)
    }

    fun refreshLocal(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        dataLoader.refreshLocal(phone)
    }

    fun isLoading(): Boolean = state.isLoading()

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

    fun contactExists(): Boolean = state.contactExists
    fun localGeneralNote(): String = state.localGeneralNote
    fun localGeneralNotePending(): Boolean = state.localGeneralNotePending
    fun companyScopeAvailable(): Boolean = state.companyScopeAvailable
    fun hasConfirmedLocalServerNote(): Boolean = state.prepared.confirmedLocalServerNote
    fun hasCompanyMainNoteScope(): Boolean = state.prepared.hasCompanyMainNoteScope
    fun hasServerRecordsFor(phone: String): Boolean = state.hasServerRecordsFor(phone)

    /** Loading progress is shown only through the non-blocking black tooltip. */
    fun serverLoadingStatusText(): String = ""

    fun companyMainNotes(phone: String): List<CallReportCompanyMainNote> =
        state.prepared.companyMainNotes.takeIf { phone == state.activePhone }.orEmpty()

    fun unscopedServerMainNote(phone: String): CallReportHistoryEvent? =
        state.prepared.unscopedServerMainNote.takeIf { phone == state.activePhone }

    fun addNotesSection(
        root: LinearLayout,
        phone: String,
        onEditCallNote: (ContactCallNote) -> Unit,
        onEditSms: (SmsMessageRecord, String) -> Unit,
    ) {
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        errorUi.addBelowContactName(root, remoteEnabled, state.loadError)
        rowsUi.addSection(
            root = root,
            phone = phone,
            remoteEnabled = remoteEnabled,
            principal = state.serverHistory.principal,
            rows = state.prepared.rows,
            latestLocalCall = state.latestLocalCall,
            localNotes = state.localNotes,
            localLoading = state.localLoading || state.prepareLoading,
            serverLoading = state.serverLoading,
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
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        errorUi.addBelowContactName(root, remoteEnabled, state.loadError)
        fullLogUi.addSection(
            root = root,
            phone = phone,
            incomingEntries = state.prepared.fullLogEntries,
            remoteEnabled = remoteEnabled,
            loading = state.isLoading(),
            errorText = state.loadError,
            openCallNoteEditor = openCallNoteEditor,
        )
    }

    fun markRendered() = prepareCoordinator.markRendered()

    fun forceNextRenderAfterDataReady() {
        prepareCoordinator.forceNextRenderAfterDataReady()
    }

    fun release() {
        state.localGeneration += 1
        state.serverGeneration += 1
        state.prepareGeneration += 1
        busyTracker.clearAll()
        loadExecutor.shutdownNow()
        prepareExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        HomeBusyTooltipUi.clear(activity)
    }

    private fun selectPhone(phone: String) {
        CallReportHistoryPhoneSelector.select(
            activity = activity,
            state = state,
            busyTracker = busyTracker,
            rowsUi = rowsUi,
            fullLogUi = fullLogUi,
            phone = phone,
        )
    }
}
