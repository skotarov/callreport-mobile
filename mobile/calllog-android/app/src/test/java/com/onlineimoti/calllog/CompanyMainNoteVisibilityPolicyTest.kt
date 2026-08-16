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
    private val pending = CallReportCompanyMainNote(
        companyId = "company-c",
        companyName = "Нова фирма",
        note = "",
        updatedAtMs = 2L,
        confirmedByServer = false,
        pending = true,
    )

    @Test
    fun `existing notes remain visible while empty company lanes stay hidden`() {
        assertEquals(
            listOf(existing),
            CompanyMainNoteVisibilityPolicy.visibleNotes(
                companyScopeAvailable = true,
                notes = listOf(existing, empty),
            ),
        )
        assertEquals(
            listOf(existing),
            CompanyMainNoteVisibilityPolicy.visibleNotes(
                companyScopeAvailable = false,
                notes = listOf(existing, empty),
            ),
        )
    }

    @Test
    fun `pending server note remains visible`() {
        assertEquals(
            listOf(pending),
            CompanyMainNoteVisibilityPolicy.visibleNotes(
                companyScopeAvailable = true,
                notes = listOf(empty, pending),
            ),
        )
    }

    @Test
    fun `empty company alone is never enough to show a company lane`() {
        assertFalse(CompanyMainNoteVisibilityPolicy.shouldShow(true, listOf(empty)))
        assertFalse(CompanyMainNoteVisibilityPolicy.shouldShow(false, listOf(empty)))
        assertTrue(CompanyMainNoteVisibilityPolicy.shouldShow(true, listOf(existing, empty)))
    }
}
