package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Builds the unfiltered Home timeline from the two device providers. Each automatic
 * page reads only the rows it needs; date headers are a display concern and must not
 * make the first visible page wait for an entire busy day to be scanned.
 */
internal object HomeTimelineLoader {
    private const val CALL_BATCH_SIZE = 500
    private const val SMS_BATCH_SIZE = 100
    private const val CRM_TIMELINE_SCAN_LIMIT = 1_000

    private val timelineSourceExecutor = Executors.newFixedThreadPool(2)

    fun page(context: Context, pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        val safePageIndex = pageIndex.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(5, 100)
        val endExclusive = HomeTimelinePageWindow.sourceRowsForPage(safePageIndex, safePageSize)
        val timeline = mergedSnapshot(context, endExclusive).rows
        return HomeTimelinePageWindow.rowsForPage(timeline, safePageIndex, safePageSize)
    }

    /** Same chronological data set for CRM before its existing filters are applied. */
    fun crmCandidates(context: Context): List<PhoneCallRecord> {
        val timeline = mergedSnapshot(context, CRM_TIMELINE_SCAN_LIMIT).rows
        val eligibleKeys = HomeCallPageLoader.crmEligiblePhoneKeys(context, timeline.map { it.number })
        return timeline.filter { call -> HomeCallPageLoader.noteKey(call.number) in eligibleKeys }
    }

    /** Exact provider pages are always read fresh, so old snapshot invalidation is no longer needed. */
    fun invalidateCache() = Unit

    /** Call Log and SMS are independent providers, so query them concurrently. */
    private fun mergedSnapshot(context: Context, wantedPerSource: Int): TimelineSnapshot {
        if (wantedPerSource <= 0) {
            return TimelineSnapshot(emptyList(), emptyList())
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
    ) {
        val rows: List<PhoneCallRecord> = (calls + sms).sortedByDescending { it.startedAt }
    }
}
