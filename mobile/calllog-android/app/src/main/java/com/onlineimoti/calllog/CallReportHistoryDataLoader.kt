package com.onlineimoti.calllog

import android.app.Activity
import android.os.Handler
import java.util.concurrent.ExecutorService

internal class CallReportHistoryDataLoader(
    private val activity: Activity,
    private val state: CallReportHistoryState,
    private val handler: Handler,
    private val executor: ExecutorService,
    private val busyTracker: CallReportHistoryBusyTracker,
    private val prepareCoordinator: CallReportHistoryPrepareCoordinator,
) {
    fun refreshServer(phone: String) {
        if (state.serverLoading) return

        val config = ConfigStore.load(activity)
        val requestedSignature = HistorySnapshotCache.remoteSignature(config)
        if (requestedSignature != state.remoteSignature) {
            state.remoteSignature = requestedSignature
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
                state.serverHistory = cached
                state.serverLoaded = true
                state.serverDataDirty = true
            } else if (
                state.serverLoaded ||
                state.serverHistory != CallReportHistoryLookupResult()
            ) {
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
            prepareCoordinator.publishIfNeeded()
            return
        }

        prepareCoordinator.invalidateForNewData()
        state.serverLoading = true
        val generation = ++state.serverGeneration
        val token = busyTracker.replaceServer()
        val requestedPhone = phone

        executor.execute {
            val result = runCatching {
                CallReportHistoryLookupClient.lookup(
                    config = config,
                    phone = requestedPhone,
                    context = activity.applicationContext,
                )
            }
            handler.post {
                busyTracker.finishServer(token)
                if (
                    activity.isFinishing ||
                    activity.isDestroyed ||
                    generation != state.serverGeneration ||
                    requestedPhone != state.activePhone
                ) return@post

                if (!CallReportRemoteAccess.isEnabled(activity)) {
                    clearServerStateAndPrepareIfNeeded()
                    return@post
                }

                state.serverLoading = false
                result.onSuccess { history ->
                    if (!state.serverLoaded || history != state.serverHistory) {
                        state.serverDataDirty = true
                    }
                    state.serverHistory = history
                    state.serverLoaded = true
                    state.loadError = ""
                }.onFailure { error ->
                    state.loadError = CallReportHistoryErrorText.from(error)
                }
                prepareCoordinator.prepareWhenDataReady(requestedPhone)
            }
        }
    }

    fun refreshLocal(phone: String) {
        if (state.localLoading) return

        prepareCoordinator.invalidateForNewData()
        state.localLoading = true
        val generation = ++state.localGeneration
        val token = busyTracker.replaceLocal()
        val requestedPhone = phone
        val appContext = activity.applicationContext

        executor.execute {
            val result = runCatching {
                HistoryBackgroundLoader.loadLocal(appContext, requestedPhone)
            }
            handler.post {
                busyTracker.finishLocal(token)
                if (
                    activity.isFinishing ||
                    activity.isDestroyed ||
                    generation != state.localGeneration ||
                    requestedPhone != state.activePhone
                ) return@post

                state.localLoading = false
                result.onSuccess { snapshot ->
                    if (snapshot != state.currentLocalSnapshot()) {
                        state.applyLocalSnapshot(snapshot)
                        state.localDataDirty = true
                    }
                }
                // Keep the last cached local snapshot if the provider read fails.
                prepareCoordinator.prepareWhenDataReady(requestedPhone)
            }
        }
    }

    private fun clearServerStateAndPrepareIfNeeded() {
        val hadServerData =
            state.serverLoaded || state.serverHistory != CallReportHistoryLookupResult()
        val errorChanged = state.loadError.isNotBlank()
        state.serverLoading = false
        state.serverLoaded = false
        state.serverHistory = CallReportHistoryLookupResult()
        state.loadError = ""
        state.remoteSignature = ""
        if (hadServerData) state.serverDataDirty = true
        if (state.activePhone.isBlank()) return
        if (state.serverDataDirty || state.localDataDirty) {
            prepareCoordinator.prepareWhenDataReady(state.activePhone)
        } else if (errorChanged) {
            prepareCoordinator.publishIfNeeded()
        }
    }
}
