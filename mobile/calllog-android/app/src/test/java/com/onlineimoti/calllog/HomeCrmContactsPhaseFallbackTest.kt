package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCrmContactsPhaseFallbackTest {
    private val phaseState = HomeCrmFilterState(phases = setOf(ContactNegotiationPhaseStore.PHASE_2))
    private val contact = PhoneCallRecord(
        number = "0888123456",
        name = "Client",
        direction = "",
        startedAt = 100L,
        durationSeconds = 0L,
    )

    @Test
    fun retriesEmptyAllClientsPhaseResult() {
        var loaded = false
        var filtered = false

        val result = HomeCrmContactsPhaseFallback.resolve(
            state = phaseState,
            filteredContacts = emptyList(),
            loadWithoutPhase = {
                loaded = true
                listOf(contact)
            },
            applyPhaseFilter = { contacts ->
                filtered = true
                contacts
            },
        )

        assertTrue(loaded)
        assertTrue(filtered)
        assertEquals(listOf(contact), result)
    }

    @Test
    fun keepsNormalNonEmptyServerResultWithoutRetry() {
        var loaded = false

        val result = HomeCrmContactsPhaseFallback.resolve(
            state = phaseState,
            filteredContacts = listOf(contact),
            loadWithoutPhase = {
                loaded = true
                emptyList()
            },
            applyPhaseFilter = { it },
        )

        assertFalse(loaded)
        assertEquals(listOf(contact), result)
    }

    @Test
    fun neverChangesPersonalOnlyPath() {
        var loaded = false

        val result = HomeCrmContactsPhaseFallback.resolve(
            state = phaseState.copy(crmOnly = true),
            filteredContacts = emptyList(),
            loadWithoutPhase = {
                loaded = true
                listOf(contact)
            },
            applyPhaseFilter = { it },
        )

        assertFalse(loaded)
        assertTrue(result.isEmpty())
    }

    @Test
    fun retryFailurePreservesOriginalEmptyResult() {
        val result = HomeCrmContactsPhaseFallback.resolve(
            state = phaseState,
            filteredContacts = emptyList(),
            loadWithoutPhase = { error("temporary failure") },
            applyPhaseFilter = { it },
        )

        assertTrue(result.isEmpty())
    }
}
