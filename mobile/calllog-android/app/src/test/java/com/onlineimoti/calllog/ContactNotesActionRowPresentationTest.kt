package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNotesActionRowPresentationTest {
    @Test
    fun `pinned action card keeps labels and reserves its full height`() {
        val pinned = ContactNotesActionRowPresentations.sticky

        assertTrue(pinned.showLabels)
        assertEquals(70, pinned.cardHeightDp)
        assertEquals(78, pinned.hostHeightDp)
    }
}
