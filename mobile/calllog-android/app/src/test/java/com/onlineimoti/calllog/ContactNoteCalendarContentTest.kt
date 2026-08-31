package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNoteCalendarContentTest {
    @Test
    fun calendarDraftContainsAllYellowNotesAndTheCurrentBlueNote() {
        val description = ContactNoteCalendarContent.appendNotes(
            baseDescription = "Име: Ивана\nТелефон: 0888123456",
            generalNotes = listOf("Жълта лична", "Жълта фирмена"),
            currentCallNote = "Синя от редактирания разговор",
            generalHeading = "Основни бележки",
            callHeading = "Бележка от разговора",
        )

        assertTrue(description.contains("Жълта лична"))
        assertTrue(description.contains("Жълта фирмена"))
        assertTrue(description.contains("Синя от редактирания разговор"))
    }

    @Test
    fun generalNoteFormNeverAddsAnUnrelatedBlueHistoryNote() {
        val description = ContactNoteCalendarContent.appendNotes(
            baseDescription = "Име: Ивана",
            generalNotes = listOf("Жълта"),
            currentCallNote = null,
            generalHeading = "Основни бележки",
            callHeading = "Бележка от разговора",
        )

        assertTrue(description.contains("Жълта"))
        assertFalse(description.contains("Бележка от разговора"))
    }
}
