package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import java.util.concurrent.Executors

/** Loads and prepares local and remote History data away from the main thread. */
internal class CallReportMergedHistoryController(
    internal val activity: Activity,
    @Suppress("unused") internal val headerUi: ContactNotesHeaderUi,
    internal val dp: (Int) -> Int,
    internal val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    internal val rerender: () -> Unit,
) {
    internal val handler = Handler(Looper.getMainLooper())
    internal val loadExecutor = Executors.newFixedThreadPool(2)
    internal val prepareExecutor = Executors.newSingleThreadExecutor()
    internal val rowsUi by lazy { CallReportHistoryRowsUi(activity, dp, roundedRect) }
    internal val fullLogUi by lazy { ContactNotesFullLogUi(activity, dp, roundedRect) }

    internal var activePhone = ""
    internal var started = false
    internal var localLoading = false
    internal var serverLoading = false
    internal var prepareLoading = false
    internal var serverLoaded = false
    internal var localDataDirty = false
    internal var serverDataDirty = false
    internal var remoteSignature = ""
    internal var localGeneration = 0
    internal var serverGeneration = 0
    internal var prepareGeneration = 0
    internal var localBusyToken = 0L
    internal var serverBusyToken = 0L
    internal val prepareBusyTokens = linkedSetOf<Long>()
    internal var lastRenderedState: HistoryRenderedState? = null
    internal var forceRenderAfterPrepare = false

    internal var localCalls: List<PhoneCallRecord> = emptyList()
    internal var latestLocalCall: PhoneCallRecord? = null
    internal var localSms: List<SmsMessageRecord> = emptyList()
    internal var localNotes: List<ContactCallNote> = emptyList()
    internal var localGeneralNote = ""
    internal var localGeneralNotePending = false
    internal var contactExists = false
    internal var companyScopeAvailable = false
    internal var serverHistory = CallReportHistoryLookupResult()
    internal var prepared = HistoryPreparedSnapshot()
    internal var loadError = ""

    fun loadOnce(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (started) return
        started = true
        refreshLocal(phone)
        refreshServer(phone)
    }

    fun refreshServer(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (serverLoading) return
        val config = ConfigStore.load(activity)
        val requestedSignature = HistorySnapshotCache.remoteSignature(config)
        if (requestedSignature != remoteSignature) {
            remoteSignature = requestedSignature
            if (serverLoaded || serverHistory != CallReportHistoryLookupResult()) {
                serverLoaded = false
                serverHistory = CallReportHistoryLookupResult()
                serverDataDirty = true
            }
        }
        if (!CallReportRemoteAccess.isEnabled(config)) {
            clearServerStateAndPrepareIfNeeded()
            return
        }
        if (!CallReportRemoteAccess.isReady(config)) {
            publishIfNeeded()
            return
        }

        invalidatePrepareForNewData()
        serverLoading = true
        val generation = ++serverGeneration
        val token = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_SERVER)
        finishServerBusy()
        serverBusyToken = token
        val requestedPhone = phone
        loadExecutor.execute {
            val result = runCatching { CallReportHistoryLookupClient.lookup(config, requestedPhone) }
            handler.post {
                finishServerBusy(token)
                if (
                    activity.isFinishing || activity.isDestroyed ||
                    generation != serverGeneration || requestedPhone != activePhone
                ) return@post
                if (!CallReportRemoteAccess.isEnabled(activity)) {
                    clearServerStateAndPrepareIfNeeded()
                    return@post
                }
                serverLoading = false
                result.onSuccess { history ->
                    if (!serverLoaded || history != serverHistory) serverDataDirty = true
                    serverHistory = history
                    serverLoaded = true
                    loadError = ""
                }.onFailure { error ->
                    // Keep the last good server snapshot visible during a temporary connection error.
                    loadError = serverErrorText(error)
                }
                prepareWhenDataReady(requestedPhone)
            }
        }
    }

    fun refreshLocal(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (localLoading) return
        invalidatePrepareForNewData()
        localLoading = true
        val generation = ++localGeneration
        val token = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_LOCAL)
        finishLocalBusy()
        localBusyToken = token
        val requestedPhone = phone
        val appContext = activity.applicationContext
        loadExecutor.execute {
            val result = runCatching { HistoryBackgroundLoader.loadLocal(appContext, requestedPhone) }
            handler.post {
                finishLocalBusy(token)
                if (
                    activity.isFinishing || activity.isDestroyed ||
                    generation != localGeneration || requestedPhone != activePhone
                ) return@post
                localLoading = false
                result.onSuccess { snapshot ->
                    if (snapshot != currentLocalSnapshot()) {
                        applyLocalSnapshot(snapshot)
                        localDataDirty = true
                    }
                }
                // On a provider error keep the last cached local snapshot instead of collapsing the list.
                prepareWhenDataReady(requestedPhone)
            }
        }
    }

    fun isLoading(): Boolean = localLoading || serverLoading || prepareLoading

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

    fun contactExists(): Boolean = contactExists
    fun localGeneralNote(): String = localGeneralNote
    fun localGeneralNotePending(): Boolean = localGeneralNotePending
    fun companyScopeAvailable(): Boolean = companyScopeAvailable
    fun hasConfirmedLocalServerNote(): Boolean = prepared.confirmedLocalServerNote
    fun hasCompanyMainNoteScope(): Boolean = prepared.hasCompanyMainNoteScope

    fun hasServerRecordsFor(phone: String): Boolean {
        if (!serverLoaded || phone.isBlank() || phone != activePhone) return false
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        if (phoneKey.isBlank()) return false
        return serverHistory.events.any { event ->
            HomeCallPageLoader.noteKey(event.phone) == phoneKey &&
                event.communicationType.equals("note", ignoreCase = true) &&
                event.note.trim().isNotBlank()
        }
    }

    /** Loading progress is shown only through the non-blocking black tooltip. */
    fun serverLoadingStatusText(): String = ""

    fun companyMainNotes(phone: String): List<CallReportCompanyMainNote> =
        prepared.companyMainNotes.takeIf { phone == activePhone }.orEmpty()

    fun unscopedServerMainNote(phone: String): CallReportHistoryEvent? =
        prepared.unscopedServerMainNote.takeIf { phone == activePhone }

    fun addNotesSection(
        root: LinearLayout,
        phone: String,
        onEditCallNote: (ContactCallNote) -> Unit,
        onEditSms: (SmsMessageRecord, String) -> Unit,
    ) {
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        addServerErrorBelowContactName(root, remoteEnabled)
        rowsUi.addSection(
            root = root,
            phone = phone,
            remoteEnabled = remoteEnabled,
            principal = serverHistory.principal,
            rows = prepared.rows,
            latestLocalCall = latestLocalCall,
            localNotes = localNotes,
            localLoading = localLoading || prepareLoading,
            serverLoading = serverLoading,
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
        addServerErrorBelowContactName(root, remoteEnabled)
        fullLogUi.addSection(
            root = root,
            phone = phone,
            incomingEntries = prepared.fullLogEntries,
            remoteEnabled = remoteEnabled,
            loading = isLoading(),
            errorText = loadError,
            openCallNoteEditor = openCallNoteEditor,
        )
    }

    fun markRendered() {
        lastRenderedState = currentRenderedState()
    }

    fun forceNextRenderAfterDataReady() {
        forceRenderAfterPrepare = true
    }

    fun release() {
        localGeneration += 1
        serverGeneration += 1
        prepareGeneration += 1
        finishLocalBusy()
        finishServerBusy()
        finishAllPrepareBusy()
        loadExecutor.shutdownNow()
        prepareExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        HomeBusyTooltipUi.clear(activity)
    }

}
