package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactNoteTopicSelectionTest {
    @Test
    fun blankSelectionDefaultsToLocal() {
        val state = ContactNoteTopicState(
            visible = true,
            companies = listOf(CallReportTopicCompany("firm-a", "Firm A")),
            includeLocalOption = true,
        )

        assertEquals(
            ContactNoteTopicState.LOCAL_COMPANY_ID,
            ContactNoteTopicSelector.resolvedSelectedCompanyId(state),
        )
    }

    @Test
    fun existingAllowedFirmRemainsSelected() {
        val state = ContactNoteTopicState(
            visible = true,
            companies = listOf(
                CallReportTopicCompany("firm-a", "Firm A"),
                CallReportTopicCompany("firm-b", "Firm B"),
            ),
            selectedCompanyId = "firm-b",
            includeLocalOption = true,
        )

        assertEquals("firm-b", ContactNoteTopicSelector.resolvedSelectedCompanyId(state))
    }

    @Test
    fun removedFirmPermissionFallsBackToLocal() {
        val state = ContactNoteTopicState(
            visible = true,
            companies = listOf(CallReportTopicCompany("firm-a", "Firm A")),
            selectedCompanyId = "firm-removed",
            includeLocalOption = true,
        )

        assertEquals(
            ContactNoteTopicState.LOCAL_COMPANY_ID,
            ContactNoteTopicSelector.resolvedSelectedCompanyId(state),
        )
    }

    @Test
    fun serverOnlySelectorFallsBackToFirstAllowedFirm() {
        val state = ContactNoteTopicState(
            visible = true,
            companies = listOf(
                CallReportTopicCompany("firm-a", "Firm A"),
                CallReportTopicCompany("firm-b", "Firm B"),
            ),
            selectedCompanyId = "",
            includeLocalOption = false,
        )

        assertEquals("firm-a", ContactNoteTopicSelector.resolvedSelectedCompanyId(state))
    }
}
