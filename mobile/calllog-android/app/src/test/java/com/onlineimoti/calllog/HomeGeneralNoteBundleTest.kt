package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGeneralNoteBundleTest {
    @Test
    fun equalLocalAndServerTextRemainSeparateRows() {
        val bundled = HomeGeneralNoteBundle.replaceServer(
            existing = "Локал",
            serverValue = ServerNoteVisuals.prefixed("Локал"),
        )

        val entries = HomeGeneralNoteBundle.entries(bundled)

        assertEquals(2, entries.size)
        assertEquals("Локал", entries[0].text)
        assertFalse(entries[0].fromServer)
        assertEquals("Локал", entries[1].text)
        assertTrue(entries[1].fromServer)
    }
}
