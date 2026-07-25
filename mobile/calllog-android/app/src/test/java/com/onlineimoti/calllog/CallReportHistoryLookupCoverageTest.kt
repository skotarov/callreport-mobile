package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class CallReportHistoryLookupCoverageTest {
    @Test
    fun phoneWithOnlyBatchCallRowsStillNeedsSinglePhoneNoteLookup() {
        val phones = listOf("0879 975 240", "0888 161 383")
        val batchEvents = listOf(
            CallReportHistoryEvent(
                communicationType = "phone",
                phone = "+359879975240",
                occurredAtMs = 1L,
            ),
            CallReportHistoryEvent(
                communicationType = "note",
                phone = "+359888161383",
                occurredAtMs = 2L,
                note = "Има бележка",
            ),
        )

        assertEquals(
            listOf("0879 975 240"),
            CallReportHistoryLookupClient.phonesMissingNoteCoverage(phones, batchEvents),
        )
    }

    @Test
    fun phoneWithBatchNoteCoverageDoesNotNeedFallback() {
        val phones = listOf("0879 975 240")
        val batchEvents = listOf(
            CallReportHistoryEvent(
                communicationType = "note",
                phone = "+359879975240",
                occurredAtMs = 1L,
                note = "Бояна",
            ),
        )

        assertEquals(
            emptyList<String>(),
            CallReportHistoryLookupClient.phonesMissingNoteCoverage(phones, batchEvents),
        )
    }
}
