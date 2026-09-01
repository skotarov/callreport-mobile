package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNoteInitialValuePolicyTest {
    @Test
    fun callRowNoteIsNotUsedAsTheGeneralNoteAfterSwitchingTabs() {
        assertFalse(
            ContactNoteInitialValuePolicy.belongsToCurrentKind(
                launchedAsGeneral = false,
                currentIsGeneral = true,
            )
        )
    }

    @Test
    fun generalNoteIsNotUsedAsTheCallNoteAfterSwitchingTabs() {
        assertFalse(
            ContactNoteInitialValuePolicy.belongsToCurrentKind(
                launchedAsGeneral = true,
                currentIsGeneral = false,
            )
        )
    }

    @Test
    fun launchValueRemainsAvailableInItsOriginalTab() {
        assertTrue(
            ContactNoteInitialValuePolicy.belongsToCurrentKind(
                launchedAsGeneral = false,
                currentIsGeneral = false,
            )
        )
    }
}
