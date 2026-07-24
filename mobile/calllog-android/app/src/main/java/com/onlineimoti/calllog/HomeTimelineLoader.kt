package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Builds the unfiltered Home timeline from the two device providers. Button pages
 * read only through the requested offset. Automatic scrolling starts with a small
 * snapshot and expands it until both providers cover the final visible day.
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
    private var groupedCache = TimedTimeline(
        loadedAtMs = 0L,
        snapshot = TimelineSnapshot(emptyList(), emptyList(), true, true),
        requestedPerSource = 0,
    )
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
            groupedCache = TimedTimeline(
                loadedAtMs = 0L,
                snapshot = TimelineSnapshot(emptyList(), emptyList(), true, true),
                requestedPerSource = 0,
            )
        }
    }

    private fun groupedPage(
        context: Context,
        pageIndex: Int,
        pageSize: Int,
    ): List<PhoneCallRecord> {
        var requestedPerSource = maxOf(
            INITIAL_GROUPED_SOURCE_ROWS,
            (pageIndex + 1) * pageSize,
        ).coerceAtMost(GROUPED_TIMELINE_SCAN_LIMIT)

        while (true) {
            val snapshot = groupedTimeline(context, requestedPerSource)
            val pages = TimelineGroupedPager.pages(
                items = snapshot.rows,
                minimumPageSize = pageSize,
                groupKey = { row -> TimelineGroupKeys.day(row.startedAt) },
            )
            val page = pages.getOrNull(pageIndex).orEmpty()

            // A page is stable only when unseen rows from both providers are older
            // than its final visible day. This prevents later SMS batches from
            // displacing Call Log rows that have already been paged.
            if (snapshot.covers(page) || snapshot.fullyExhausted ||
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
            if (fresh && (
                    groupedCache.snapshot.fullyExhausted ||
                        groupedCache.requestedPerSource >= requestedPerSource
                    )
            ) {
                return groupedCache.snapshot
            }
            loadGeneration = groupedCacheGeneration
        }

        val loaded = mergedSnapshot(context, requestedPerSource)
        synchronized(groupedCacheLock) {
            // A refresh may invalidate the cache while provider queries are in flight.
            if (loadGeneration == groupedCacheGeneration) {
                groupedCache = TimedTimeline(
                    loadedAtMs = now,
                    snapshot = loaded,
                    requestedPerSource = requestedPerSource,
                )
            }
        }
        return loaded
    }

    /** Call Log and SMS are independent providers, so query them concurrently. */
    private fun mergedSnapshot(context: Context, wantedPerSource: Int): TimelineSnapshot {
        if (wantedPerSource <= 0) {
            return TimelineSnapshot(emptyList(), emptyList(), true, true)
        }
        val callsFuture = timelineSourceExecutor.submit<List<PhoneCallRecord>> {
            readCalls(context, wantedPerSource)
        }
        val smsFuture = timelineSourceExecutor.submit<List<PhoneCallRecord>> {
            readSms(context, wantedPerSource)
        }
        val calls = await(callsFuture, emptyList())
        val sms = await(smsFuture, emptyList())
        return TimelineSnapshot(
            calls = calls,
            sms = sms,
            callsExhausted = calls.size < wantedPerSource,
            smsExhausted = sms.size < wantedPerSource,
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

    private fun <T> await(future: Future<T>, fallback: T): T {
        return try {
            future.get()
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private data class TimelineSnapshot(
        val calls: List<PhoneCallRecord>,
        val sms: List<PhoneCallRecord>,
        val callsExhausted: Boolean,
        val smsExhausted: Boolean,
    ) {
        val rows: List<PhoneCallRecord> = (calls + sms).sortedByDescending { it.startedAt }
        val fullyExhausted: Boolean = callsExhausted && smsExhausted

        fun covers(page: List<PhoneCallRecord>): Boolean {
            val lastVisible = page.lastOrNull() ?: return fullyExhausted
            val lastVisibleDay = TimelineGroupKeys.day(lastVisible.startedAt)
            return sourceCovers(calls, callsExhausted, lastVisible, lastVisibleDay) &&
                sourceCovers(sms, smsExhausted, lastVisible, lastVisibleDay)
        }

        private fun sourceCovers(
            source: List<PhoneCallRecord>,
            exhausted: Boolean,
            lastVisible: PhoneCallRecord,
            lastVisibleDay: Long?,
        ): Boolean {
            if (exhausted) return true
            val oldestLoaded = source.lastOrNull() ?: return false
            val oldestLoadedDay = TimelineGroupKeys.day(oldestLoaded.startedAt)
            return if (lastVisibleDay != null && oldestLoadedDay != null) {
                oldestLoadedDay < lastVisibleDay
            } else {
                oldestLoaded.startedAt < lastVisible.startedAt
            }
        }
    }

    private data class TimedTimeline(
        val loadedAtMs: Long,
        val snapshot: TimelineSnapshot,
        val requestedPerSource: Int,
    )
}
