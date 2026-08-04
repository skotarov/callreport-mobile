package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyMainNoteVisibilityPolicyTest {
    private val existing = CallReportCompanyMainNote(
        companyId = "company-a",
        companyName = "Maxim",
        note = "Съществуваща бележка",
        updatedAtMs = 1L,
        confirmedByServer = true,
        pending = false,
    )
    private val empty = CallReportCompanyMainNote(
        companyId = "company-b",
        companyName = "Имоти",
        note = "",
        updatedAtMs = 0L,
        confirmedByServer = false,
        pending = false,
        placeholder = true,
    )

    @Test
    fun `without CRM keeps existing server notes but hides empty company lanes`() {
        assertEquals(
            listOf(existing),
            CompanyMainNoteVisibilityPolicy.visibleNotes(
                companyScopeAvailable = false,
                notes = listOf(existing, empty),
            ),
        )
    }

    @Test
    fun `with company scope keeps empty lanes for adding notes`() {
        assertEquals(
            listOf(existing, empty),
            CompanyMainNoteVisibilityPolicy.visibleNotes(
                companyScopeAvailable = true,
                notes = listOf(existing, empty),
            ),
        )
    }

    @Test
    fun `existing note is enough to show company section without CRM`() {
        assertTrue(CompanyMainNoteVisibilityPolicy.shouldShow(false, listOf(existing, empty)))
        assertFalse(CompanyMainNoteVisibilityPolicy.shouldShow(false, listOf(empty)))
    }
}
