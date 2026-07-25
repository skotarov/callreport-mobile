package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNotesSnapshotDeletionTest {
    @Test
    fun deletingOneBlueNotePreservesOtherNotesOnTheSameCall() {
        val key = "879975240|1000|out"
        val notes = mapOf(
            key to HomeCallNote(
                text = "Локална",
                updatedAtMs = 1L,
                fromServer = false,
                serverClientEventId = "local-1",
                relatedNotes = listOf(
                    HomeCallNote(
                        text = "За изтриване",
                        updatedAtMs = 2L,
                        fromServer = true,
                        serverClientEventId = "server-2",
                    ),
                    HomeCallNote(
                        text = "Друга фирма",
                        updatedAtMs = 3L,
                        fromServer = true,
                        companyId = "company-b",
                        serverClientEventId = "server-3",
                    ),
                ),
            ),
        )

        val updated = HomeNotesSnapshotCache.removeDeletedCallNote(
            notes = notes,
            phoneKey = "879975240",
            callAtMs = 1000L,
            direction = "out",
            serverClientEventId = "server-2",
        )

        val remaining = updated.getValue(key).expandedNotes()
        assertEquals(listOf("Локална", "Друга фирма"), remaining.map { it.text })
        assertFalse(remaining.any { it.serverClientEventId == "server-2" })
    }

    @Test
    fun oldSnapshotWithoutEventIdIsClearedForTheConcreteCall() {
        val targetKey = "879975240|1000|out"
        val otherKey = "879975240|2000|in"
        val notes = mapOf(
            targetKey to HomeCallNote("Стара бележка", 1L, fromServer = true),
            otherKey to HomeCallNote("Друг разговор", 2L, fromServer = true),
        )

        val updated = HomeNotesSnapshotCache.removeDeletedCallNote(
            notes = notes,
            phoneKey = "879975240",
            callAtMs = 1000L,
            direction = "out",
            serverClientEventId = "newer-server-id",
        )

        assertFalse(targetKey in updated)
        assertTrue(otherKey in updated)
    }

    @Test
    fun pendingBlankServerEditBecomesAHomeTombstone() {
        val event = CallReportQueuedNote(
            clientEventId = "server-note-1",
            phone = "+359879975240",
            direction = "out",
            occurredAtMs = 1234L,
            durationSeconds = 10L,
            note = "",
            contactName = "Майстора",
            updatedAtMs = 5678L,
            editExistingServerNote = true,
        ).toHistoryEvent()

        assertEquals("server-note-1", event.clientEventId)
        assertEquals("", event.note)
        assertEquals(1234L, event.occurredAtMs)
        assertEquals(5678L, event.updatedAtMs)
    }
}
