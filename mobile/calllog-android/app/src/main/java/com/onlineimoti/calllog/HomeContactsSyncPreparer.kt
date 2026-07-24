package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class HomeContactsSyncPreparer(
    private val context: Context,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var prepared = false

    fun prepareOnce() {
        if (prepared) return
        prepared = true
        executor.execute {
            try {
                Thread.sleep(INITIAL_SYNC_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@execute
            }
            CallReportRuntime.ensureContactsSync(context.applicationContext)
        }
    }

    fun release() {
        executor.shutdownNow()
    }

    private companion object {
        const val INITIAL_SYNC_DELAY_MS = 500L
    }
}
