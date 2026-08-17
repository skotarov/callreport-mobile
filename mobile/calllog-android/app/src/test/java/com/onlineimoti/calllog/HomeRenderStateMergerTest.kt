package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRenderStateMergerTest {
    private val call = PhoneCallRecord(
        number = "+359 888 123 456",
        name = "",
        direction = "in",
        startedAt = 1_000L,
        durationSeconds = 30L,
        providerId = "1",
    )
    private val phoneKey = HomeCallPageLoader.noteKey(call.number)
    private val callKey = HomeCallNotesResolver.keyFor(call)
    private val note = HomeCallNote("Уговорка", 2_000L, fromServer = true, companyId = "firm-a")

    @Test
    fun provisionalEmptyStageKeepsVisibleNameAndNotes() {
        val remembered = linkedMapOf(phoneKey to "Иван Иванов")
        val result = HomeRenderStateMerger.merge(
            calls = listOf(call),
            incoming = HomeRenderData(listOf(call), emptyMap(), emptyMap(), emptyMap()),
            currentContactNotes = mapOf(phoneKey to "Обща бележка"),
            currentContactNames = mapOf(phoneKey to "Иван Иванов"),
            currentCallNotes = mapOf(callKey to note),
            rememberedNames = remembered,
            mode = HomeRenderMergeMode.PROVISIONAL,
        )

        assertEquals("Иван Иванов", result.contactNamesByNumber[phoneKey])
        assertEquals("Обща бележка", result.contactNotesByNumber[phoneKey])
        assertEquals(note, result.callNotesByCall[callKey])
    }

    @Test
    fun supplementalStageChangesOnlyProvidedValues() {
        val result = HomeRenderStateMerger.merge(
            calls = listOf(call),
            incoming = HomeRenderData(
                calls = listOf(call),
                contactNotesByNumber = emptyMap(),
                contactNamesByNumber = mapOf(phoneKey to "Иван Петров"),
                callNotesByCall = emptyMap(),
            ),
            currentContactNotes = mapOf(phoneKey to "Обща бележка"),
            currentContactNames = mapOf(phoneKey to "Иван Иванов"),
            currentCallNotes = mapOf(callKey to note),
            rememberedNames = linkedMapOf(phoneKey to "Иван Иванов"),
            mode = HomeRenderMergeMode.SUPPLEMENTAL,
        )

        assertEquals("Иван Петров", result.contactNamesByNumber[phoneKey])
        assertEquals("Обща бележка", result.contactNotesByNumber[phoneKey])
        assertEquals(note, result.callNotesByCall[callKey])
    }

    @Test
    fun supplementalBlankTombstoneRemovesDeletedYellowNote() {
        val result = HomeRenderStateMerger.merge(
            calls = listOf(call),
            incoming = HomeRenderData(
                calls = listOf(call),
                contactNotesByNumber = mapOf(phoneKey to ""),
                contactNamesByNumber = emptyMap(),
                callNotesByCall = emptyMap(),
            ),
            currentContactNotes = mapOf(phoneKey to "Изтрита локална бележка"),
            currentContactNames = mapOf(phoneKey to "Иван Иванов"),
            currentCallNotes = mapOf(callKey to note),
            rememberedNames = linkedMapOf(phoneKey to "Иван Иванов"),
            mode = HomeRenderMergeMode.SUPPLEMENTAL,
        )

        assertFalse(result.contactNotesByNumber.containsKey(phoneKey))
        assertEquals("Иван Иванов", result.contactNamesByNumber[phoneKey])
        assertEquals(note, result.callNotesByCall[callKey])
    }

    @Test
    fun authoritativeStageCanRemoveDeletedNotesButKeepsKnownName() {
        val result = HomeRenderStateMerger.merge(
            calls = listOf(call),
            incoming = HomeRenderData(listOf(call), emptyMap(), emptyMap(), emptyMap()),
            currentContactNotes = mapOf(phoneKey to "Стара бележка"),
            currentContactNames = mapOf(phoneKey to "Иван Иванов"),
            currentCallNotes = mapOf(callKey to note),
            rememberedNames = linkedMapOf(phoneKey to "Иван Иванов"),
            mode = HomeRenderMergeMode.AUTHORITATIVE,
        )

        assertEquals("Иван Иванов", result.contactNamesByNumber[phoneKey])
        assertTrue(result.contactNotesByNumber.isEmpty())
        assertTrue(result.callNotesByCall.isEmpty())
    }

    @Test
    fun stagedPageDoesNotCarryNotesFromAnotherCall() {
        val nextCall = call.copy(number = "+359 899 000 111", startedAt = 3_000L, providerId = "2")
        val result = HomeRenderStateMerger.merge(
            calls = listOf(nextCall),
            incoming = HomeRenderData(listOf(nextCall), emptyMap(), emptyMap(), emptyMap()),
            currentContactNotes = mapOf(phoneKey to "Стара бележка"),
            currentContactNames = mapOf(phoneKey to "Иван Иванов"),
            currentCallNotes = mapOf(callKey to note),
            rememberedNames = linkedMapOf(phoneKey to "Иван Иванов"),
            mode = HomeRenderMergeMode.PROVISIONAL,
        )

        assertFalse(result.contactNotesByNumber.containsKey(phoneKey))
        assertFalse(result.callNotesByCall.containsKey(callKey))
    }
}
