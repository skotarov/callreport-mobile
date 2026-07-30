package com.onlineimoti.calllog

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object IncomingCallLookupExecutors {
    const val LOOKUP_DEADLINE_MS = 4_500L
    const val POPUP_HISTORY_LIMIT = 20

    private const val CONTACT_QUEUE_SIZE = 8
    private const val LOCAL_ROWS_QUEUE_SIZE = 8
    private const val LOOKUP_QUEUE_SIZE = 12
    private const val HISTORY_QUEUE_SIZE = 12

    val contact = pool(1, CONTACT_QUEUE_SIZE)
    val localRows = pool(1, LOCAL_ROWS_QUEUE_SIZE)
    val lookup = pool(2, LOOKUP_QUEUE_SIZE)
    val history = pool(1, HISTORY_QUEUE_SIZE)
    val timeout = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }

    private fun pool(threads: Int, queueSize: Int) = ThreadPoolExecutor(
        threads,
        threads,
        20L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(queueSize),
        ThreadPoolExecutor.AbortPolicy(),
    )
}
