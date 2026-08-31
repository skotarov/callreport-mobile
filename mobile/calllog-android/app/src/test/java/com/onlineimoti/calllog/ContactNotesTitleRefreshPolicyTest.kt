package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactNotesTitleRefreshPolicyTest {
    @Test
    fun newlyEditedContactsNameReplacesHistoryTitle() {
        assertEquals(
            "Ново име",
            ContactNotesTitleRefreshPolicy.updatedTitle(
                currentTitle = "Старо име",
                contactsDisplayName = "  Ново име  ",
            ),
        )
    }

    @Test
    fun unavailableContactsNamePreservesHistoryTitle() {
        assertEquals(
            "Име от историята",
            ContactNotesTitleRefreshPolicy.updatedTitle(
                currentTitle = "Име от историята",
                contactsDisplayName = "   ",
            ),
        )
    }
}
