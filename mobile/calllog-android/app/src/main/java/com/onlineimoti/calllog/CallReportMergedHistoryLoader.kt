package com.onlineimoti.calllog

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Coordinates local/server loading and publishes coherent prepared History snapshots. */
internal class CallReportMergedHistoryLoader(
    private val activity: Activity,
    private val rerender: () -> Unit,
    private val resetPages: () -> Unit,
) {
    internal val state = CallReportMergedHistoryState()
    private val handler = Handler(Looper.getMainLooper())
    private val loadExecutor = Executors.newFixedThreadPool(2)
    private val prepareExecutor = Executors.newSingleThreadExecutor()
    private val busy = HistoryBusyTokens(activity)

    private var localLoading = false
    private var serverLoading = false
    private var prepareLoading = false
    private var localGeneration = 0
    private var serverGeneration = 0
    private var prepareGeneration = 0

    fun loadOnce(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (state.started) return
        state.started = true
        refreshLocal(phone)
        refreshServer(phone)
    }

    fun refreshServer(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (serverLoading) return
        val config = ConfigStore.load(activity)
        val requestedSignature = HistorySnapshotCache.remoteSignature(config)
        if (requestedSignature != state.remoteSignature) {
            state.remoteSignature = requestedSignature
            if (state.serverLoaded || state.serverHistory != CallReportHistoryLookupResult()) {
                state.serverLoaded = false
                state.serverHistory = CallReportHistoryLookupResult()
                state.serverDataDirty = true
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
        val token = busy.beginServer()
        val requestedPhone = phone
        loadExecutor.execute {
            val result = runCatching { CallReportHistoryLookupClient.lookup(config, requestedPhone) }
            handler.post {
                busy.finishServer(token)
                if (activity.isFinishing || activity.isDestroyed ||
                    generation != serverGeneration || requestedPhone != state.activePhone
                ) return@post
                if (!CallReportRemoteAccess.isEnabled(activity)) {
                    clearServerStateAndPrepareIfNeeded()
                    return@post
                }
                serverLoading = false
                result.onSuccess { history ->
                    if (!state.serverLoaded || history != state.serverHistory) state.serverDataDirty = true
                    state.serverHistory = history
                    state.serverLoaded = true
                    state.loadError = ""
                }.onFailure { error -> state.loadError = HistoryServerErrorText.from(error) }
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
        val token = busy.beginLocal()
        val requestedPhone = phone
        val appContext = activity.applicationContext
        loadExecutor.execute {
            val result = runCatching { HistoryBackgroundLoader.loadLocal(appContext, requestedPhone) }
            handler.post {
                busy.finishLocal(token)
                if (activity.isFinishing || activity.isDestroyed ||
                    generation != localGeneration || requestedPhone != state.activePhone
                ) return@post
                localLoading = false
                result.onSuccess { snapshot ->
                    if (snapshot != state.currentLocalSnapshot()) {
                        state.applyLocalSnapshot(snapshot)
                        state.localDataDirty = true
                    }
                }
                prepareWhenDataReady(requestedPhone)
            }
        }
    }

    fun isLoading(): Boolean = localLoading || serverLoading || prepareLoading
    fun isLocalPreparing(): Boolean = localLoading || prepareLoading
    fun isServerLoading(): Boolean = serverLoading

    fun hasServerRecordsFor(phone: String): Boolean {
        if (!state.serverLoaded || phone.isBlank() || phone != state.activePhone) return false
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        if (phoneKey.isBlank()) return false
        return state.serverHistory.events.any { event ->
            HomeCallPageLoader.noteKey(event.phone) == phoneKey &&
                event.communicationType.equals("note", ignoreCase = true) && event.note.trim().isNotBlank()
        }
    }

    fun markRendered() {
        state.lastRenderedState = state.renderedState(isLoading())
    }

    fun forceNextRenderAfterDataReady() {
        state.forceRenderAfterPrepare = true
    }

    fun release() {
        localGeneration += 1
        serverGeneration += 1
        prepareGeneration += 1
        busy.finishAll()
        loadExecutor.shutdownNow()
        prepareExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        HomeBusyTooltipUi.clear(activity)
    }

    private fun prepareWhenDataReady(phone: String) {
        if (phone.isBlank() || phone != state.activePhone || localLoading || serverLoading) return
        if (!state.localDataDirty && !state.serverDataDirty) {
            publishIfNeeded()
            return
        }
        schedulePrepare(phone)
    }

    private fun schedulePrepare(phone: String) {
        if (phone.isBlank() || phone != state.activePhone) return
        prepareLoading = true
        val generation = ++prepareGeneration
        val token = busy.beginPrepare()
        val appContext = activity.applicationContext
        val requestedPhone = phone
        val requestedSignature = state.remoteSignature
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        val loaded = state.serverLoaded
        val history = state.serverHistory
        val calls = state.localCalls.toList()
        val sms = state.localSms.toList()
        val notes = state.localNotes.toList()
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
                busy.finishPrepare(token)
                if (activity.isFinishing || activity.isDestroyed ||
                    generation != prepareGeneration || requestedPhone != state.activePhone
                ) return@post
                prepareLoading = false
                result.onSuccess {
                    state.prepared = it
                    state.localDataDirty = false
                    state.serverDataDirty = false
                    HistorySnapshotCache.putMemory(
                        requestedPhone,
                        HistoryCachedState(
                            local = state.currentLocalSnapshot(),
                            serverHistory = state.serverHistory,
                            serverLoaded = state.serverLoaded,
                            prepared = state.prepared,
                            remoteSignature = requestedSignature,
                        ),
                    )
                }
                publishIfNeeded()
            }
        }
    }

    private fun publishIfNeeded() {
        val nextState = state.renderedState(isLoading())
        if (!state.forceRenderAfterPrepare && nextState == state.lastRenderedState) return
        state.forceRenderAfterPrepare = false
        state.lastRenderedState = nextState
        rerender()
    }

    private fun invalidatePrepareForNewData() {
        if (!prepareLoading && !busy.hasPrepare()) return
        prepareGeneration += 1
        prepareLoading = false
        busy.finishAllPrepare()
    }

    private fun selectPhone(phone: String) {
        if (state.activePhone == phone) return
        localGeneration += 1
        serverGeneration += 1
        prepareGeneration += 1
        busy.finishAll()
        localLoading = false
        serverLoading = false
        prepareLoading = false
        state.reset(phone, HistorySnapshotCache.remoteSignature(ConfigStore.load(activity)))
        resetPages()

        val memoryState = HistorySnapshotCache.memoryState(phone)
        val cachedLocal = memoryState?.local ?: HistoryBackgroundLoader.cachedLocal(activity.applicationContext, phone)
        if (cachedLocal != null) state.applyLocalSnapshot(cachedLocal)
        if (memoryState != null && memoryState.remoteSignature == state.remoteSignature) {
            state.serverHistory = memoryState.serverHistory
            state.serverLoaded = memoryState.serverLoaded && state.remoteSignature.isNotBlank()
            state.prepared = memoryState.prepared
        } else if (cachedLocal != null) {
            state.prepared = HistoryBackgroundLoader.prepareCachedLocal(phone, cachedLocal)
        }
        if (cachedLocal != null) {
            HistorySnapshotCache.putMemory(
                phone,
                HistoryCachedState(
                    local = state.currentLocalSnapshot(),
                    serverHistory = state.serverHistory,
                    serverLoaded = state.serverLoaded,
                    prepared = state.prepared,
                    remoteSignature = state.remoteSignature,
                ),
            )
        }
    }

    private fun clearServerStateAndPrepareIfNeeded() {
        val hadServerData = state.serverLoaded || state.serverHistory != CallReportHistoryLookupResult()
        val errorChanged = state.loadError.isNotBlank()
        serverLoading = false
        state.serverLoaded = false
        state.serverHistory = CallReportHistoryLookupResult()
        state.loadError = ""
        state.remoteSignature = ""
        if (hadServerData) state.serverDataDirty = true
        if (state.activePhone.isBlank()) return
        if (state.serverDataDirty || state.localDataDirty) prepareWhenDataReady(state.activePhone)
        else if (errorChanged) publishIfNeeded()
    }
}
