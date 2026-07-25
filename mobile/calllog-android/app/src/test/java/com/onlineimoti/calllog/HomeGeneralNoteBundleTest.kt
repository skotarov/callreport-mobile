package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGeneralNoteBundleTest {
    @Test
    fun equalLocalAndServerTextRemainSeparateRows() {
        val entries = HomeGeneralNoteBundle.distinctEntries(
            listOf(
                HomeGeneralNoteEntry("Локал", fromServer = false),
                HomeGeneralNoteEntry("Локал", fromServer = true),
            ),
        )

        assertEquals(2, entries.size)
        assertEquals("Локал", entries[0].text)
        assertFalse(entries[0].fromServer)
        assertEquals("Локал", entries[1].text)
        assertTrue(entries[1].fromServer)
    }

    @Test
    fun duplicateRowsFromTheSameSourceCollapse() {
        val entries = HomeGeneralNoteBundle.distinctEntries(
            listOf(
                HomeGeneralNoteEntry("  Локал ", fromServer = false),
                HomeGeneralNoteEntry("локал", fromServer = false),
            ),
        )

        assertEquals(1, entries.size)
        assertEquals("Локал", entries.single().text)
        assertFalse(entries.single().fromServer)
    }
}
