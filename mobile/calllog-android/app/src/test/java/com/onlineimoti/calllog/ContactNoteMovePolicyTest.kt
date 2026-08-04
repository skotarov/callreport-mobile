package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNoteMovePolicyTest {
    @Test
    fun `confirmed server company note can move even when Local is the only other target`() {
        assertTrue(
            ContactNoteMovePolicy.canStart(
                selectedCompanyId = "company-a",
                value = ContactNoteScopeValue(
                    text = "note",
                    serverClientEventId = "event-a",
                    confirmedServer = true,
                ),
                currentText = "edited note",
                companyCount = 1,
            ),
        )
    }

    @Test
    fun `local note can move to a server company`() {
        val draft = ContactNoteFormDraft(
            phone = "+359888000000",
            title = "Test",
            direction = "outgoing",
            callAt = 1234L,
            durationSeconds = 20L,
        )
        val localEventId = ContactNoteMovePolicy.localEventId(draft)
        assertTrue(
            ContactNoteMovePolicy.canStart(
                selectedCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID,
                value = ContactNoteScopeValue(
                    text = "local note",
                    serverClientEventId = localEventId,
                    confirmedServer = true,
                ),
                currentText = "local note",
                companyCount = 1,
            ),
        )
        assertNotNull(ContactNoteMovePolicy.localCoordinates(localEventId))
    }

    @Test
    fun `pending server and blank notes cannot enter move mode`() {
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
        assertFalse(ContactNoteMovePolicy.canStart(
            ContactNoteTopicState.LOCAL_COMPANY_ID,
            ContactNoteScopeValue("note", "", true),
            "note",
            1,
        ))
    }

    @Test
    fun `target can be Local or a different server company`() {
        assertTrue(ContactNoteMovePolicy.canTarget("company-a", "company-b"))
        assertTrue(ContactNoteMovePolicy.canTarget("company-a", ContactNoteTopicState.LOCAL_COMPANY_ID))
        assertTrue(ContactNoteMovePolicy.canTarget(ContactNoteTopicState.LOCAL_COMPANY_ID, "company-a"))
        assertFalse(ContactNoteMovePolicy.canTarget("company-a", "company-a"))
        assertFalse(ContactNoteMovePolicy.canTarget("company-a", ""))
    }
}
