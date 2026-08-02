package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Loads and prepares local and remote History data away from the main thread. */
internal class CallReportMergedHistoryController(
    private val activity: Activity,
    @Suppress("unused") private val headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val rerender: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val loadExecutor = Executors.newFixedThreadPool(2)
    private val prepareExecutor = Executors.newSingleThreadExecutor()
    private val rowsUi by lazy { CallReportHistoryRowsUi(activity, dp, roundedRect) }
    private val fullLogUi by lazy { ContactNotesFullLogUi(activity, dp, roundedRect) }

    private var activePhone = ""
    private var started = false
    private var localLoading = false
    private var serverLoading = false
    private var prepareLoading = false
    private var serverLoaded = false
    private var localDataDirty = false
    private var serverDataDirty = false
    private var remoteSignature = ""
    private var localGeneration = 0
    private var serverGeneration = 0
    private var prepareGeneration = 0
    private var localBusyToken = 0L
    private var serverBusyToken = 0L
    private val prepareBusyTokens = linkedSetOf<Long>()
    private var lastRenderedState: HistoryRenderedState? = null
    private var forceRenderAfterPrepare = false

    private var localCalls: List<PhoneCallRecord> = emptyList()
    private var latestLocalCall: PhoneCallRecord? = null
    private var localSms: List<SmsMessageRecord> = emptyList()
    private var localNotes: List<ContactCallNote> = emptyList()
    private var localGeneralNote = ""
    private var localGeneralNotePending = false
    private var contactExists = false
    private var companyScopeAvailable = false
    private var serverHistory = CallReportHistoryLookupResult()
    private var prepared = HistoryPreparedSnapshot()
    private var loadError = ""

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
            val cached = if (requestedSignature.isNotBlank()) {
                CallReportHistoryDiskCache.read(
                    activity.applicationContext,
                    config,
                    listOf(phone),
                )
            } else {
                null
            }
            if (cached != null) {
                serverHistory = cached
                serverLoaded = true
                serverDataDirty = true
            } else if (serverLoaded || serverHistory != CallReportHistoryLookupResult()) {
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
            val result = runCatching {
                CallReportHistoryLookupClient.lookup(
                    config = config,
                    phone = requestedPhone,
                    context = activity.applicationContext,
                )
            }
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

    /**
     * A last successful cached server snapshot may be rendered while a live request
     * is still running. Wait only when no server snapshot exists at all.
     */
    private fun prepareWhenDataReady(phone: String) {
        if (
            phone.isBlank() || phone != activePhone || localLoading ||
            (serverLoading && !serverLoaded)
        ) return
        if (!localDataDirty && !serverDataDirty) {
            publishIfNeeded()
            return
        }
        schedulePrepare(phone)
    }

    private fun schedulePrepare(phone: String) {
        if (phone.isBlank() || phone != activePhone) return
        prepareLoading = true
        val generation = ++prepareGeneration
        val token = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_PREPARE)
        prepareBusyTokens += token
        val appContext = activity.applicationContext
        val requestedPhone = phone
        val requestedSignature = remoteSignature
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        val loaded = serverLoaded
        val history = serverHistory
        val calls = localCalls.toList()
        val sms = localSms.toList()
        val notes = localNotes.toList()
        prepareExecutor.execute {
            val result = runCatching {
                HistoryBackgroundLoader.prepare(
                    context = appContext,
                    phone = requestedPhone,
                    remoteEnabled = remoteEnabled,
                    serverLoaded = loaded,
                    history = history,
                    localCalls = calls,
                    localSms = sms,
                    localNotes = notes,
                )
            }
            handler.post {
                finishPrepareBusy(token)
                if (
                    activity.isFinishing || activity.isDestroyed ||
                    generation != prepareGeneration || requestedPhone != activePhone
                ) return@post
                prepareLoading = false
                result.onSuccess {
                    prepared = it
                    localDataDirty = false
                    serverDataDirty = false
                    HistorySnapshotCache.putMemory(
                        requestedPhone,
                        HistoryCachedState(
                            local = currentLocalSnapshot(),
                            serverHistory = serverHistory,
                            serverLoaded = serverLoaded,
                            prepared = prepared,
                            remoteSignature = requestedSignature,
                        ),
                    )
                }
                publishIfNeeded()
            }
        }
    }

    /** Rebuild only when visible data differs; loading flags alone do not recreate the page. */
    private fun publishIfNeeded() {
        val nextState = currentRenderedState()
        if (!forceRenderAfterPrepare && nextState == lastRenderedState) return
        forceRenderAfterPrepare = false
        lastRenderedState = nextState
        rerender()
    }

    private fun currentRenderedState(): HistoryRenderedState = HistoryRenderedState(
        showLoadingPlaceholder = isLoading() && !hasVisibleHistoryContent(),
        serverLoaded = serverLoaded,
        localCalls = localCalls,
        latestLocalCall = latestLocalCall,
        localSms = localSms,
        localNotes = localNotes,
        localGeneralNote = localGeneralNote,
        localGeneralNotePending = localGeneralNotePending,
        contactExists = contactExists,
        companyScopeAvailable = companyScopeAvailable,
        serverHistory = serverHistory,
        prepared = prepared,
        loadError = loadError,
    )

    private fun hasVisibleHistoryContent(): Boolean =
        localGeneralNote.isNotBlank() || prepared.rows.isNotEmpty() || prepared.fullLogEntries.isNotEmpty() ||
            prepared.companyMainNotes.any { note -> note.note.isNotBlank() } || prepared.unscopedServerMainNote != null

    private fun invalidatePrepareForNewData() {
        if (!prepareLoading && prepareBusyTokens.isEmpty()) return
        prepareGeneration += 1
        prepareLoading = false
        finishAllPrepareBusy()
    }

    private fun selectPhone(phone: String) {
        if (activePhone == phone) return
        activePhone = phone
        started = false
        localGeneration += 1
        serverGeneration += 1
        prepareGeneration += 1
        finishLocalBusy()
        finishServerBusy()
        finishAllPrepareBusy()
        localLoading = false
        serverLoading = false
        prepareLoading = false
        serverLoaded = false
        localDataDirty = false
        serverDataDirty = false
        val config = ConfigStore.load(activity)
        remoteSignature = HistorySnapshotCache.remoteSignature(config)
        applyLocalSnapshot(HistoryLocalSnapshot())
        serverHistory = CallReportHistoryLookupResult()
        prepared = HistoryPreparedSnapshot()
        loadError = ""
        lastRenderedState = null
        forceRenderAfterPrepare = false
        rowsUi.resetPage()
        fullLogUi.resetPage()

        val appContext = activity.applicationContext
        val memoryState = HistorySnapshotCache.memoryState(phone)
        val cachedLocal = memoryState?.local ?: HistoryBackgroundLoader.cachedLocal(appContext, phone)
        if (cachedLocal != null) applyLocalSnapshot(cachedLocal)

        if (memoryState != null && memoryState.remoteSignature == remoteSignature) {
            serverHistory = memoryState.serverHistory
            serverLoaded = memoryState.serverLoaded && remoteSignature.isNotBlank()
            prepared = memoryState.prepared
        } else {
            val cachedServer = if (remoteSignature.isNotBlank()) {
                CallReportHistoryDiskCache.read(appContext, config, listOf(phone))
            } else {
                null
            }
            if (cachedServer != null) {
                serverHistory = cachedServer
                serverLoaded = true
                serverDataDirty = true
            }
            if (cachedLocal != null) {
                prepared = HistoryBackgroundLoader.prepareCachedLocal(phone, cachedLocal)
            }
        }

        if (cachedLocal != null || serverLoaded) {
            HistorySnapshotCache.putMemory(
                phone,
                HistoryCachedState(
                    local = currentLocalSnapshot(),
                    serverHistory = serverHistory,
                    serverLoaded = serverLoaded,
                    prepared = prepared,
                    remoteSignature = remoteSignature,
                ),
            )
        }
    }

    private fun currentLocalSnapshot(): HistoryLocalSnapshot = HistoryLocalSnapshot(
        calls = localCalls,
        latestCall = latestLocalCall,
        sms = localSms,
        callNotes = localNotes,
        generalNote = localGeneralNote,
        generalNotePending = localGeneralNotePending,
        contactExists = contactExists,
        companyScopeAvailable = companyScopeAvailable,
    )

    private fun applyLocalSnapshot(snapshot: HistoryLocalSnapshot) {
        localCalls = snapshot.calls
        latestLocalCall = snapshot.latestCall ?: snapshot.calls.firstOrNull()
        localSms = snapshot.sms
        localNotes = snapshot.callNotes
        localGeneralNote = snapshot.generalNote
        localGeneralNotePending = snapshot.generalNotePending
        contactExists = snapshot.contactExists
        companyScopeAvailable = snapshot.companyScopeAvailable
    }

    private fun addServerErrorBelowContactName(root: LinearLayout, remoteEnabled: Boolean) {
        if (!remoteEnabled || loadError.isBlank()) return
        root.addView(TextView(activity).apply {
            text = loadError
            textSize = 12.5f
            setTextColor(Color.rgb(185, 28, 28))
            setPadding(dp(2), 0, dp(2), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }, minOf(1, root.childCount))
    }

    private fun clearServerStateAndPrepareIfNeeded() {
        val hadServerData = serverLoaded || serverHistory != CallReportHistoryLookupResult()
        val errorChanged = loadError.isNotBlank()
        serverLoading = false
        serverLoaded = false
        serverHistory = CallReportHistoryLookupResult()
        loadError = ""
        remoteSignature = ""
        if (hadServerData) serverDataDirty = true
        if (activePhone.isBlank()) return
        if (serverDataDirty || localDataDirty) prepareWhenDataReady(activePhone)
        else if (errorChanged) publishIfNeeded()
    }

    private fun finishLocalBusy(token: Long = localBusyToken) {
        if (token <= 0L) return
        if (localBusyToken == token) localBusyToken = 0L
        HomeBusyTooltipUi.end(activity, token)
    }

    private fun finishServerBusy(token: Long = serverBusyToken) {
        if (token <= 0L) return
        if (serverBusyToken == token) serverBusyToken = 0L
        HomeBusyTooltipUi.end(activity, token)
    }

    private fun finishPrepareBusy(token: Long) {
        if (token <= 0L) return
        prepareBusyTokens.remove(token)
        HomeBusyTooltipUi.end(activity, token)
    }

    private fun finishAllPrepareBusy() {
        prepareBusyTokens.toList().forEach(::finishPrepareBusy)
    }

    private fun serverErrorText(error: Throwable): String {
        val message = error.message.orEmpty().trim()
        val httpStatus = Regex("\\bHTTP\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (httpStatus != null) {
            return when (httpStatus) {
                400 -> "Сървър: невалидна заявка (400)"
                401 -> "Сървър: невалиден access token (401)"
                403 -> "Сървър: достъпът е отказан (403)"
                404 -> "Сървър: history_lookup.php не е намерен (404)"
                408 -> "Сървър: изтече времето за изчакване (408)"
                429 -> "Сървър: твърде много заявки (429)"
                in 500..599 -> "Сървър: вътрешна грешка ($httpStatus)"
                else -> "Сървър: HTTP $httpStatus"
            }
        }
        return when (rootCause(error)) {
            is java.net.UnknownHostException -> "Сървър: адресът не е открит"
            is java.net.ConnectException -> "Сървър: няма връзка със сървъра"
            is java.net.SocketTimeoutException -> "Сървър: изтече времето за изчакване"
            is org.json.JSONException -> "Сървър: невалиден JSON отговор"
            else -> {
                val safeMessage = message.replace(Regex("\\s+"), " ").take(120)
                if (safeMessage.isBlank() || safeMessage.equals("History lookup failed", ignoreCase = true)) {
                    "Сървър: неуспешно зареждане на историята"
                } else {
                    "Сървър: $safeMessage"
                }
            }
        }
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private data class HistoryRenderedState(
        val showLoadingPlaceholder: Boolean,
        val serverLoaded: Boolean,
        val localCalls: List<PhoneCallRecord>,
        val latestLocalCall: PhoneCallRecord?,
        val localSms: List<SmsMessageRecord>,
        val localNotes: List<ContactCallNote>,
        val localGeneralNote: String,
        val localGeneralNotePending: Boolean,
        val contactExists: Boolean,
        val companyScopeAvailable: Boolean,
        val serverHistory: CallReportHistoryLookupResult,
        val prepared: HistoryPreparedSnapshot,
        val loadError: String,
    )
}
