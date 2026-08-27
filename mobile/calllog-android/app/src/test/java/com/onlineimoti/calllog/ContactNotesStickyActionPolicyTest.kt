package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNotesStickyActionPolicyTest {
    @Test
    fun raisedCallButtonOverlaysTheListWithoutGrowingTheModeBar() {
        assertTrue(
            ContactNotesCallButtonOverlayPolicy.bottomMargin(modeBarHeightDp = 64) == 32,
        )
    }

    @Test
    fun actionRowStaysNormalWhileItsTopIsStillVisible() {
        assertFalse(
            ContactNotesStickyActionPolicy.shouldStick(
                actionTopOnScreen = 101,
                viewportTopOnScreen = 100,
            ),
        )
    }

    @Test
    fun actionRowPinsExactlyWhenItStartsLeavingTheViewport() {
        assertTrue(
            ContactNotesStickyActionPolicy.shouldStick(
                actionTopOnScreen = 100,
                viewportTopOnScreen = 100,
            ),
        )
        assertTrue(
            ContactNotesStickyActionPolicy.shouldStick(
                actionTopOnScreen = 40,
                viewportTopOnScreen = 100,
            ),
        )
    }

    @Test
    fun compactIdentityUsesTheSameStateAsThePinnedActionRow() {
        assertFalse(
            ContactNotesStickyActionPolicy.shouldShowCompactIdentity(
                originalNameTopOnScreen = 201,
                topBarBottomOnScreen = 200,
                actionsPinned = false,
            ),
        )
        assertTrue(
            ContactNotesStickyActionPolicy.shouldShowCompactIdentity(
                originalNameTopOnScreen = null,
                topBarBottomOnScreen = 200,
                actionsPinned = true,
            ),
        )
    }

    @Test
    fun compactIdentityAndActionsChangeAtTheSameScrollThreshold() {
        val viewportTop = 100

        val beforeThreshold = ContactNotesStickyActionPolicy.shouldStick(
            actionTopOnScreen = viewportTop + 1,
            viewportTopOnScreen = viewportTop,
        )
        assertFalse(beforeThreshold)
        assertFalse(
            ContactNotesStickyActionPolicy.shouldShowCompactIdentity(
                originalNameTopOnScreen = viewportTop + 1,
                topBarBottomOnScreen = viewportTop,
                actionsPinned = beforeThreshold,
            ),
        )

        val atThreshold = ContactNotesStickyActionPolicy.shouldStick(
            actionTopOnScreen = viewportTop,
            viewportTopOnScreen = viewportTop,
        )
        assertTrue(atThreshold)
        assertTrue(
            ContactNotesStickyActionPolicy.shouldShowCompactIdentity(
                originalNameTopOnScreen = viewportTop + 1,
                topBarBottomOnScreen = viewportTop,
                actionsPinned = atThreshold,
            ),
        )
    }
}
