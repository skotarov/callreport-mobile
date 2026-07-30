package com.onlineimoti.calllog

import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView

/** Wait for all source loads, then publish one coherent snapshot. */
internal fun CallReportMergedHistoryController.prepareWhenDataReady(phone: String) {
    if (phone.isBlank() || phone != activePhone || localLoading || serverLoading) return
    if (!localDataDirty && !serverDataDirty) {
        publishIfNeeded()
        return
    }
    schedulePrepare(phone)
}

internal fun CallReportMergedHistoryController.schedulePrepare(phone: String) {
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
internal fun CallReportMergedHistoryController.publishIfNeeded() {
    val nextState = currentRenderedState()
    if (!forceRenderAfterPrepare && nextState == lastRenderedState) return
    forceRenderAfterPrepare = false
    lastRenderedState = nextState
    rerender()
}

internal fun CallReportMergedHistoryController.currentRenderedState(): HistoryRenderedState = HistoryRenderedState(
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

internal fun CallReportMergedHistoryController.hasVisibleHistoryContent(): Boolean =
    localGeneralNote.isNotBlank() || prepared.rows.isNotEmpty() || prepared.fullLogEntries.isNotEmpty() ||
        prepared.companyMainNotes.any { note -> note.note.isNotBlank() } || prepared.unscopedServerMainNote != null

internal fun CallReportMergedHistoryController.invalidatePrepareForNewData() {
    if (!prepareLoading && prepareBusyTokens.isEmpty()) return
    prepareGeneration += 1
    prepareLoading = false
    finishAllPrepareBusy()
}

internal fun CallReportMergedHistoryController.selectPhone(phone: String) {
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
    remoteSignature = HistorySnapshotCache.remoteSignature(ConfigStore.load(activity))
    applyLocalSnapshot(HistoryLocalSnapshot())
    serverHistory = CallReportHistoryLookupResult()
    prepared = HistoryPreparedSnapshot()
    loadError = ""
    lastRenderedState = null
    forceRenderAfterPrepare = false
    rowsUi.resetPage()
    fullLogUi.resetPage()

    val memoryState = HistorySnapshotCache.memoryState(phone)
    val cachedLocal = memoryState?.local ?: HistoryBackgroundLoader.cachedLocal(activity.applicationContext, phone)
    if (cachedLocal != null) applyLocalSnapshot(cachedLocal)
    if (memoryState != null && memoryState.remoteSignature == remoteSignature) {
        serverHistory = memoryState.serverHistory
        serverLoaded = memoryState.serverLoaded && remoteSignature.isNotBlank()
        prepared = memoryState.prepared
    } else if (cachedLocal != null) {
        prepared = HistoryBackgroundLoader.prepareCachedLocal(phone, cachedLocal)
    }
    if (cachedLocal != null) {
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

internal fun CallReportMergedHistoryController.currentLocalSnapshot(): HistoryLocalSnapshot = HistoryLocalSnapshot(
    calls = localCalls,
    latestCall = latestLocalCall,
    sms = localSms,
    callNotes = localNotes,
    generalNote = localGeneralNote,
    generalNotePending = localGeneralNotePending,
    contactExists = contactExists,
    companyScopeAvailable = companyScopeAvailable,
)

internal fun CallReportMergedHistoryController.applyLocalSnapshot(snapshot: HistoryLocalSnapshot) {
    localCalls = snapshot.calls
    latestLocalCall = snapshot.latestCall ?: snapshot.calls.firstOrNull()
    localSms = snapshot.sms
    localNotes = snapshot.callNotes
    localGeneralNote = snapshot.generalNote
    localGeneralNotePending = snapshot.generalNotePending
    contactExists = snapshot.contactExists
    companyScopeAvailable = snapshot.companyScopeAvailable
}

internal fun CallReportMergedHistoryController.addServerErrorBelowContactName(root: LinearLayout, remoteEnabled: Boolean) {
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

internal fun CallReportMergedHistoryController.clearServerStateAndPrepareIfNeeded() {
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

internal fun CallReportMergedHistoryController.finishLocalBusy(token: Long = localBusyToken) {
    if (token <= 0L) return
    if (localBusyToken == token) localBusyToken = 0L
    HomeBusyTooltipUi.end(activity, token)
}

internal fun CallReportMergedHistoryController.finishServerBusy(token: Long = serverBusyToken) {
    if (token <= 0L) return
    if (serverBusyToken == token) serverBusyToken = 0L
    HomeBusyTooltipUi.end(activity, token)
}

internal fun CallReportMergedHistoryController.finishPrepareBusy(token: Long) {
    if (token <= 0L) return
    prepareBusyTokens.remove(token)
    HomeBusyTooltipUi.end(activity, token)
}

internal fun CallReportMergedHistoryController.finishAllPrepareBusy() {
    prepareBusyTokens.toList().forEach(::finishPrepareBusy)
}

internal fun CallReportMergedHistoryController.serverErrorText(error: Throwable): String {
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

internal fun CallReportMergedHistoryController.rootCause(error: Throwable): Throwable {
    var current = error
    while (current.cause != null && current.cause !== current) current = current.cause!!
    return current
}

internal data class HistoryRenderedState(
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
