package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNoteMovePolicyTest {
    @Test
    fun `confirmed server company note can enter move mode`() {
        assertTrue(
            ContactNoteMovePolicy.canStart(
                selectedCompanyId = "company-a",
                value = ContactNoteScopeValue(
                    text = "note",
                    serverClientEventId = "event-a",
                    confirmedServer = true,
                ),
                currentText = "edited note",
                companyCount = 2,
            ),
        )
    }

    @Test
    fun `local pending and blank notes cannot enter move mode`() {
        assertFalse(ContactNoteMovePolicy.canStart(
            ContactNoteTopicState.LOCAL_COMPANY_ID,
            ContactNoteScopeValue("note", "event", true),
            "note",
            2,
        ))
        assertFalse(ContactNoteMovePolicy.canStart(
            "company-a",
            ContactNoteScopeValue("note", "event", false),
            "note",
            2,
        ))
        assertFalse(ContactNoteMovePolicy.canStart(
            "company-a",
            ContactNoteScopeValue("note", "event", true),
            "",
            2,
        ))
    }

    @Test
    fun `target must be a different real company`() {
        assertTrue(ContactNoteMovePolicy.canTarget("company-a", "company-b"))
        assertFalse(ContactNoteMovePolicy.canTarget("company-a", "company-a"))
        assertFalse(ContactNoteMovePolicy.canTarget("company-a", ContactNoteTopicState.LOCAL_COMPANY_ID))
        assertFalse(ContactNoteMovePolicy.canTarget("company-a", ""))
    }
}
