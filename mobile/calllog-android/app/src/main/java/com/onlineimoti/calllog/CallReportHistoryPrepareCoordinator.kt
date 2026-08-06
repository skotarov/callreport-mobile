package com.onlineimoti.calllog

import android.app.Activity
import android.os.Handler
import java.util.concurrent.ExecutorService

internal class CallReportHistoryPrepareCoordinator(
    private val activity: Activity,
    private val state: CallReportHistoryState,
    private val handler: Handler,
    private val executor: ExecutorService,
    private val busyTracker: CallReportHistoryBusyTracker,
    private val rerender: () -> Unit,
) {
    fun prepareWhenDataReady(phone: String) {
        if (
            phone.isBlank() ||
            phone != state.activePhone ||
            state.localLoading ||
            (state.serverLoading && !state.serverLoaded)
        ) return

        if (!state.localDataDirty && !state.serverDataDirty) {
            publishIfNeeded()
            return
        }
        schedulePrepare(phone)
    }

    fun invalidateForNewData() {
        if (!state.prepareLoading && !busyTracker.hasPrepareWork()) return
        state.prepareGeneration += 1
        state.prepareLoading = false
        busyTracker.finishAllPrepare()
    }

    fun markRendered() {
        state.lastRenderedState = state.currentRenderedState()
    }

    fun forceNextRenderAfterDataReady() {
        state.forceRenderAfterPrepare = true
    }

    fun publishIfNeeded() {
        val nextState = state.currentRenderedState()
        if (!state.forceRenderAfterPrepare && nextState == state.lastRenderedState) return
        state.forceRenderAfterPrepare = false
        state.lastRenderedState = nextState
        rerender()
    }

    private fun schedulePrepare(phone: String) {
        if (phone.isBlank() || phone != state.activePhone) return

        state.prepareLoading = true
        val generation = ++state.prepareGeneration
        val token = busyTracker.beginPrepare()
        val appContext = activity.applicationContext
        val requestedPhone = phone
        val requestedSignature = state.remoteSignature
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        val loaded = state.serverLoaded
        val history = state.serverHistory
        val calls = state.localCalls.toList()
        val sms = state.localSms.toList()
        val notes = state.localNotes.toList()

        executor.execute {
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
                busyTracker.finishPrepare(token)
                if (
                    activity.isFinishing ||
                    activity.isDestroyed ||
                    generation != state.prepareGeneration ||
                    requestedPhone != state.activePhone
                ) return@post

                state.prepareLoading = false
                result.onSuccess { prepared ->
                    state.prepared = prepared
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
}
