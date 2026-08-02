package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class PostCallLookupRemoteRowsTest {
    @Test
    fun popupShowsDedicatedServerGeneralNoteWithoutConversationRows() {
        val history = CallReportHistoryLookupResult(
            companyMainNotes = listOf(
                CallReportHistoryCompanyMainNote(
                    serverId = "main-only",
                    phone = "+359888111222",
                    companyId = "firm-1",
                    companyName = "Фирма 1",
                    note = "Само обща бележка",
                    updatedAtMs = 200L,
                ),
            ),
        )

        val rows = PostCallLookupRemoteRows.fromHistory(history, "0888 111 222")

        assertEquals(1, rows.size)
        assertEquals(PostCallLookupRemoteRow.Kind.GENERAL_NOTE, rows.single().kind)
        assertEquals("Само обща бележка", rows.single().note)
    }

    @Test
    fun popupShowsDedicatedServerGeneralNoteAndLatestBlueNote() {
        val history = CallReportHistoryLookupResult(
            principal = CallReportHistoryPrincipal(
                companies = listOf(CallReportHistoryCompany("firm-1", "Фирма 1")),
            ),
            events = listOf(
                CallReportHistoryEvent(
                    serverId = "call-note-1",
                    communicationType = "note",
                    phone = "+359888111222",
                    direction = "in",
                    occurredAtMs = 100L,
                    note = "Синя бележка",
                    companyId = "firm-1",
                ),
            ),
            companyMainNotes = listOf(
                CallReportHistoryCompanyMainNote(
                    serverId = "main-1",
                    phone = "+359888111222",
                    companyId = "firm-1",
                    companyName = "Фирма 1",
                    note = "Обща бележка от сървъра",
                    updatedAtMs = 200L,
                ),
            ),
        )

        val rows = PostCallLookupRemoteRows.fromHistory(history, "0888 111 222")

        assertEquals(2, rows.size)
        assertEquals(PostCallLookupRemoteRow.Kind.GENERAL_NOTE, rows[0].kind)
        assertEquals("Обща бележка от сървъра", rows[0].note)
        assertEquals(PostCallLookupRemoteRow.Kind.CALL_NOTE, rows[1].kind)
        assertEquals("Синя бележка", rows[1].note)
    }
}
