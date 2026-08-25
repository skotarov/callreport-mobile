package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSyncStatusStateTest {
    @Test
    fun zeroPendingItemsHidesIndicator() {
        val state = homeSyncStatusState(0)
        assertFalse(state.visible)
        assertEquals("", state.badgeText)
    }

    @Test
    fun pendingItemsShowExactCountUpToNinetyNine() {
        assertEquals("1", homeSyncStatusState(1).badgeText)
        assertEquals("99", homeSyncStatusState(99).badgeText)
        assertTrue(homeSyncStatusState(99).visible)
        assertFalse(homeSyncStatusState(99).hasIssue)
    }

    @Test
    fun issueIsShownOnlyWhenThereArePendingItems() {
        assertTrue(homeSyncStatusState(2, hasIssue = true).hasIssue)
        assertFalse(homeSyncStatusState(0, hasIssue = true).hasIssue)
    }

    @Test
    fun countsAboveNinetyNineUseCappedBadge() {
        assertEquals("99+", homeSyncStatusState(100).badgeText)
        assertEquals("99+", homeSyncStatusState(250).badgeText)
    }
}
