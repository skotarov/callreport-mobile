package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNoteScopeSwitchCoordinatorTest {
    @Test
    fun persistsLocalDeletionBeforeOpeningCompanyScope() {
        val operations = mutableListOf<String>()

        val switched = ContactNoteScopeSwitchCoordinator.switch(
            currentCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID,
            nextCompanyId = "company-1",
            editorReady = true,
            persistCurrent = {
                operations += "save-local"
                true
            },
            applyNext = { operations += "open-$it" },
        )

        assertTrue(switched)
        assertEquals(listOf("save-local", "open-company-1"), operations)
    }

    @Test
    fun failedSaveKeepsCurrentScopeVisible() {
        var nextApplied = false

        val switched = ContactNoteScopeSwitchCoordinator.switch(
            currentCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID,
            nextCompanyId = "company-1",
            editorReady = true,
            persistCurrent = { false },
            applyNext = { nextApplied = true },
        )

        assertFalse(switched)
        assertFalse(nextApplied)
    }

    @Test
    fun initialSpinnerBindingDoesNotWriteExistingText() {
        var persisted = false
        var selected = ""

        val switched = ContactNoteScopeSwitchCoordinator.switch(
            currentCompanyId = "",
            nextCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID,
            editorReady = false,
            persistCurrent = {
                persisted = true
                true
            },
            applyNext = { selected = it },
        )

        assertTrue(switched)
        assertFalse(persisted)
        assertEquals(ContactNoteTopicState.LOCAL_COMPANY_ID, selected)
    }

    @Test
    fun selectingSameScopeDoesNotWriteAgain() {
        var persisted = false

        val switched = ContactNoteScopeSwitchCoordinator.switch(
            currentCompanyId = "company-1",
            nextCompanyId = "company-1",
            editorReady = true,
            persistCurrent = {
                persisted = true
                true
            },
            applyNext = {},
        )

        assertTrue(switched)
        assertFalse(persisted)
    }
}
