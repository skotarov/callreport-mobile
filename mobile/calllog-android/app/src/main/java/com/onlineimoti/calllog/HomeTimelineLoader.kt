package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Builds the unfiltered Home timeline from the two device providers. Button pages
 * read only through the requested offset. Automatic scrolling starts with a small
 * snapshot and expands it only when a later page or an unfinished day needs more.
 */
internal object HomeTimelineLoader {
    private const val CALL_BATCH_SIZE = 500
    private const val SMS_BATCH_SIZE = 100
    private const val CRM_TIMELINE_SCAN_LIMIT = 1_000
    private const val INITIAL_GROUPED_SOURCE_ROWS = 40
    private const val GROUPED_TIMELINE_SCAN_LIMIT = 2_000
    private const val GROUPED_TIMELINE_CACHE_MS = 30_000L

    private val timelineSourceExecutor = Executors.newFixedThreadPool(2)
    private val groupedCacheLock = Any()
    private var groupedCache = TimedTimeline(0L, emptyList(), 0, false)
    private var groupedCacheGeneration = 0

    fun page(context: Context, pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        val safePageIndex = pageIndex.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(5, 100)
        if (PageLoadingModeStore.usesPrefetch(context)) {
            return groupedPage(
                context = context.applicationContext,
                pageIndex = safePageIndex,
                pageSize = safePageSize,
            )
        }

        val endExclusive = ((safePageIndex + 1).toLong() * safePageSize.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val timeline = mergedSnapshot(context, endExclusive).rows
        return timeline.drop(safePageIndex * safePageSize).take(safePageSize)
    }

    /** Same chronological data set for CRM before its existing filters are applied. */
    fun crmCandidates(context: Context): List<PhoneCallRecord> {
        val timeline = mergedSnapshot(context, CRM_TIMELINE_SCAN_LIMIT).rows
        val eligibleKeys = HomeCallPageLoader.crmEligiblePhoneKeys(context, timeline.map { it.number })
        return timeline.filter { call -> HomeCallPageLoader.noteKey(call.number) in eligibleKeys }
    }

    fun invalidateCache() {
        synchronized(groupedCacheLock) {
            groupedCacheGeneration += 1
            groupedCache = TimedTimeline(0L, emptyList(), 0, false)
        }
    }

    private fun groupedPage(
        context: Context,
        pageIndex: Int,
        pageSize: Int,
    ): List<PhoneCallRecord> {
        var requestedPerSource = maxOf(
            INITIAL_GROUPED_SOURCE_ROWS,
            (pageIndex + 2) * pageSize,
        ).coerceAtMost(GROUPED_TIMELINE_SCAN_LIMIT)

        while (true) {
            val snapshot = groupedTimeline(context, requestedPerSource)
            val pages = TimelineGroupedPager.pages(
                items = snapshot.rows,
                minimumPageSize = pageSize,
                groupKey = { row -> TimelineGroupKeys.day(row.startedAt) },
            )
            val page = pages.getOrNull(pageIndex).orEmpty()

            // A following page proves that the requested page ends at a complete
            // day boundary. Otherwise expand until the final visible day is whole.
            val hasFollowingPage = pageIndex < pages.lastIndex
            if (hasFollowingPage || snapshot.exhausted ||
                requestedPerSource >= GROUPED_TIMELINE_SCAN_LIMIT
            ) {
                return page
            }

            val expanded = maxOf(requestedPerSource + pageSize, requestedPerSource * 2)
                .coerceAtMost(GROUPED_TIMELINE_SCAN_LIMIT)
            if (expanded == requestedPerSource) return page
            requestedPerSource = expanded
        }
    }

    private fun groupedTimeline(context: Context, requestedPerSource: Int): TimelineSnapshot {
        val now = System.currentTimeMillis()
        val loadGeneration: Int
        synchronized(groupedCacheLock) {
            val fresh = now - groupedCache.loadedAtMs < GROUPED_TIMELINE_CACHE_MS
            if (fresh && (groupedCache.exhausted || groupedCache.requestedPerSource >= requestedPerSource)) {
                return TimelineSnapshot(groupedCache.rows, groupedCache.exhausted)
            }
            loadGeneration = groupedCacheGeneration
        }

        val loaded = mergedSnapshot(context, requestedPerSource)
        synchronized(groupedCacheLock) {
            // A refresh may invalidate the cache while provider queries are in flight.
            if (loadGeneration == groupedCacheGeneration) {
                groupedCache = TimedTimeline(
                    loadedAtMs = now,
                    rows = loaded.rows,
                    requestedPerSource = requestedPerSource,
                    exhausted = loaded.exhausted,
                )
            }
        }
        return loaded
    }

    /** Call Log and SMS are independent providers, so query them concurrently. */
    private fun mergedSnapshot(context: Context, wantedPerSource: Int): TimelineSnapshot {
        if (wantedPerSource <= 0) return TimelineSnapshot(emptyList(), true)
        val callsFuture = timelineSourceExecutor.submit<List<PhoneCallRecord>> {
            readCalls(context, wantedPerSource)
        }
        val smsFuture = timelineSourceExecutor.submit<List<PhoneCallRecord>> {
            readSms(context, wantedPerSource)
        }
        val calls = await(callsFuture)
        val sms = await(smsFuture)
        return TimelineSnapshot(
            rows = (calls + sms).sortedByDescending { it.startedAt },
            exhausted = calls.size < wantedPerSource && sms.size < wantedPerSource,
        )
    }

    private fun readCalls(context: Context, wanted: Int): List<PhoneCallRecord> {
        if (wanted <= 0) return emptyList()
        val rows = mutableListOf<PhoneCallRecord>()
        var offset = 0
        while (rows.size < wanted) {
            val requested = minOf(CALL_BATCH_SIZE, wanted - rows.size)
            val batch = PhoneCallReader.recentCalls(context, limit = requested, offset = offset)
            if (batch.isEmpty()) break
            rows += batch
            offset += batch.size
            if (batch.size < requested) break
        }
        return rows
    }

    private fun readSms(context: Context, wanted: Int): List<PhoneCallRecord> {
        if (wanted <= 0) return emptyList()
        val rows = mutableListOf<PhoneCallRecord>()
        var offset = 0
        while (rows.size < wanted) {
            val requested = minOf(SMS_BATCH_SIZE, wanted - rows.size)
            val batch = SmsMessageReader.recentMessages(context, offset = offset, limit = requested)
            if (batch.isEmpty()) break
            rows += batch.mapNotNull { message ->
                message.address.takeIf { it.isNotBlank() }?.let { address ->
                    PhoneCallRecord(
                        number = address,
                        name = "",
                        direction = if (message.isOutgoing) "sms_out" else "sms_in",
                        startedAt = message.timestampMs,
                        durationSeconds = 0L,
                        smsBody = message.body,
                        providerId = message.providerId,
                    )
                }
            }
            offset += batch.size
            if (batch.size < requested) break
        }
        return rows
    }

    private fun <T> await(future: Future<T>): T {
        return try {
            future.get()
        } catch (error: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw error
        }
    }

    private data class TimelineSnapshot(
        val rows: List<PhoneCallRecord>,
        val exhausted: Boolean,
    )

    private data class TimedTimeline(
        val loadedAtMs: Long,
        val rows: List<PhoneCallRecord>,
        val requestedPerSource: Int,
        val exhausted: Boolean,
    )
}
