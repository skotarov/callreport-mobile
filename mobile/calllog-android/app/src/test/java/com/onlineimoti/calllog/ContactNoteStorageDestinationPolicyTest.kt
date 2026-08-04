package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactNoteStorageDestinationPolicyTest {
    @Test
    fun `local option is classified as local storage`() {
        assertEquals(
            ContactNoteStorageDestination.LOCAL,
            ContactNoteStorageDestinationPolicy.resolve(
                ContactNoteTopicState.LOCAL_COMPANY_ID,
                fallbackLocalOnly = false,
            ),
        )
    }

    @Test
    fun `real company id is classified as server storage`() {
        assertEquals(
            ContactNoteStorageDestination.SERVER,
            ContactNoteStorageDestinationPolicy.resolve(
                selectedCompanyId = "company-maxim",
                fallbackLocalOnly = true,
            ),
        )
    }

    @Test
    fun `blank destination uses local-only fallback`() {
        assertEquals(
            ContactNoteStorageDestination.LOCAL,
            ContactNoteStorageDestinationPolicy.resolve(
                selectedCompanyId = "",
                fallbackLocalOnly = true,
            ),
        )
    }
}
