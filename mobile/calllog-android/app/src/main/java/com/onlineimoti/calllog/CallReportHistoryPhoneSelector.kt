package com.onlineimoti.calllog

import android.app.Activity

internal object CallReportHistoryPhoneSelector {
    fun select(
        activity: Activity,
        state: CallReportHistoryState,
        busyTracker: CallReportHistoryBusyTracker,
        rowsUi: CallReportHistoryRowsUi,
        fullLogUi: ContactNotesFullLogUi,
        phone: String,
    ) {
        if (state.activePhone == phone) return

        state.activePhone = phone
        state.started = false
        state.localGeneration += 1
        state.serverGeneration += 1
        state.prepareGeneration += 1
        busyTracker.clearAll()
        state.localLoading = false
        state.serverLoading = false
        state.prepareLoading = false
        state.serverLoaded = false
        state.localDataDirty = false
        state.serverDataDirty = false

        val config = ConfigStore.load(activity)
        state.remoteSignature = HistorySnapshotCache.remoteSignature(config)
        state.applyLocalSnapshot(HistoryLocalSnapshot())
        state.serverHistory = CallReportHistoryLookupResult()
        state.prepared = HistoryPreparedSnapshot()
        state.loadError = ""
        state.lastRenderedState = null
        state.forceRenderAfterPrepare = false
        rowsUi.resetPage()
        fullLogUi.resetPage()

        val appContext = activity.applicationContext
        val memoryState = HistorySnapshotCache.memoryState(phone)
        val cachedLocal =
            memoryState?.local ?: HistoryBackgroundLoader.cachedLocal(appContext, phone)
        if (cachedLocal != null) state.applyLocalSnapshot(cachedLocal)

        if (memoryState != null && memoryState.remoteSignature == state.remoteSignature) {
            state.serverHistory = memoryState.serverHistory
            state.serverLoaded =
                memoryState.serverLoaded && state.remoteSignature.isNotBlank()
            state.prepared = memoryState.prepared
        } else {
            val cachedServer = if (state.remoteSignature.isNotBlank()) {
                CallReportHistoryDiskCache.read(appContext, config, listOf(phone))
            } else {
                null
            }
            if (cachedServer != null) {
                state.serverHistory = cachedServer
                state.serverLoaded = true
                state.serverDataDirty = true
            }
            if (cachedLocal != null) {
                state.prepared = HistoryBackgroundLoader.prepareCachedLocal(phone, cachedLocal)
            }
        }

        if (cachedLocal != null || state.serverLoaded) {
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
}
