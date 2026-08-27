package com.onlineimoti.calllog

import org.json.JSONObject
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
                direction = "out",
                occurredAtMs = 2L,
                note = "Има бележка към разговор",
            ),
        )

        assertEquals(
            listOf("0879 975 240"),
            CallReportHistoryLookupClient.phonesMissingNoteCoverage(phones, batchEvents),
        )
    }

    @Test
    fun phoneWithConcreteBatchCallNoteDoesNotNeedFallback() {
        val phones = listOf("0879 975 240")
        val batchEvents = listOf(
            CallReportHistoryEvent(
                communicationType = "note",
                phone = "+359879975240",
                direction = "out",
                occurredAtMs = 1L,
                note = "Бояна",
            ),
        )

        assertEquals(
            emptyList<String>(),
            CallReportHistoryLookupClient.phonesMissingNoteCoverage(phones, batchEvents),
        )
    }

    @Test
    fun generalBatchNoteDoesNotHideMissingCallNotes() {
        val phones = listOf("0879 975 240")
        val batchEvents = listOf(
            CallReportHistoryEvent(
                communicationType = "note",
                clientEventId = "rm:note:general:0879975240",
                phone = "+359879975240",
                occurredAtMs = 1L,
                note = "Майстор на коли Максим - Бояна",
                companyId = "maxim",
            ),
        )

        assertEquals(
            phones,
            CallReportHistoryLookupClient.phonesMissingNoteCoverage(phones, batchEvents),
        )
    }

    @Test
    fun explicitBatchCoverageAvoidsSinglesForPhonesWithoutBlueNotes() {
        val phones = listOf("0879 975 240", "0888 161 383")

        assertEquals(
            emptyList<String>(),
            CallReportHistoryLookupClient.phonesMissingNoteCoverage(
                phones = phones,
                batchEvents = emptyList(),
                coveredPhoneKeys = setOf("879975240", "888161383"),
            ),
        )
    }

    @Test
    fun parserReadsServerBatchCoverageUsingTheMobilePhoneKey() {
        val result = CallReportHistoryLookupClient.parsePayload(
            JSONObject("""{"ok":true,"coverage":{"phone_keys":["+359879975240"]}}"""),
        )

        assertEquals(setOf("879975240"), result.coveredPhoneKeys)
    }
}
