package com.onlineimoti.calllog

import android.app.Activity

internal class CallReportHistoryBusyTracker(private val activity: Activity) {
    private var localToken = 0L
    private var serverToken = 0L
    private val prepareTokens = linkedSetOf<Long>()

    fun replaceLocal(): Long {
        val token = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_LOCAL)
        finishLocal()
        localToken = token
        return token
    }

    fun replaceServer(): Long {
        val token = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_SERVER)
        finishServer()
        serverToken = token
        return token
    }

    fun beginPrepare(): Long {
        val token = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_PREPARE)
        prepareTokens += token
        return token
    }

    fun finishLocal(token: Long = localToken) {
        if (token <= 0L) return
        if (localToken == token) localToken = 0L
        HomeBusyTooltipUi.end(activity, token)
    }

    fun finishServer(token: Long = serverToken) {
        if (token <= 0L) return
        if (serverToken == token) serverToken = 0L
        HomeBusyTooltipUi.end(activity, token)
    }

    fun finishPrepare(token: Long) {
        if (token <= 0L) return
        prepareTokens.remove(token)
        HomeBusyTooltipUi.end(activity, token)
    }

    fun hasPrepareWork(): Boolean = prepareTokens.isNotEmpty()

    fun finishAllPrepare() {
        prepareTokens.toList().forEach(::finishPrepare)
    }

    fun clearAll() {
        finishLocal()
        finishServer()
        finishAllPrepare()
    }
}
