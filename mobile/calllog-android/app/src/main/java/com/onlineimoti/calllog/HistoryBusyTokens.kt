package com.onlineimoti.calllog

import android.app.Activity

/** Keeps History tooltip tokens balanced across replaced and cancelled loads. */
internal class HistoryBusyTokens(private val activity: Activity) {
    private var local = 0L
    private var server = 0L
    private val prepare = linkedSetOf<Long>()

    fun beginLocal(): Long {
        val token = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_LOCAL)
        finishLocal()
        local = token
        return token
    }

    fun beginServer(): Long {
        val token = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_SERVER)
        finishServer()
        server = token
        return token
    }

    fun beginPrepare(): Long = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_PREPARE).also {
        prepare += it
    }

    fun finishLocal(token: Long = local) {
        if (token <= 0L) return
        if (local == token) local = 0L
        HomeBusyTooltipUi.end(activity, token)
    }

    fun finishServer(token: Long = server) {
        if (token <= 0L) return
        if (server == token) server = 0L
        HomeBusyTooltipUi.end(activity, token)
    }

    fun finishPrepare(token: Long) {
        if (token <= 0L) return
        prepare.remove(token)
        HomeBusyTooltipUi.end(activity, token)
    }

    fun hasPrepare(): Boolean = prepare.isNotEmpty()

    fun finishAllPrepare() {
        prepare.toList().forEach(::finishPrepare)
    }

    fun finishAll() {
        finishLocal()
        finishServer()
        finishAllPrepare()
    }
}
