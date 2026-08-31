package com.onlineimoti.calllog

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRefreshRenderPolicyTest {
    @After
    fun tearDown() {
        HomeRefreshRenderPolicy.clear()
    }

    @Test
    fun keepsRowsForExactlyOneRender() {
        HomeRefreshRenderPolicy.requestKeepExistingRows()

        assertTrue(HomeRefreshRenderPolicy.consumeKeepExistingRows())
        assertFalse(HomeRefreshRenderPolicy.consumeKeepExistingRows())
    }

    @Test
    fun providerRefreshKeepsVisibleOrdinaryCallLogInsteadOfBlankingIt() {
        assertTrue(
            HomeRefreshRenderPolicy.shouldKeepRowsForProviderRefresh(
                activeSearchQuery = "",
                crmCallLogEnabled = false,
                crmContactsMode = false,
                hasRenderedRows = true,
            ),
        )
    }

    @Test
    fun providerRefreshDoesNotRetainRowsForSearchOrOtherTimelineModes() {
        assertFalse(HomeRefreshRenderPolicy.shouldKeepRowsForProviderRefresh("име", false, false, true))
        assertFalse(HomeRefreshRenderPolicy.shouldKeepRowsForProviderRefresh("", true, false, true))
        assertFalse(HomeRefreshRenderPolicy.shouldKeepRowsForProviderRefresh("", false, true, true))
        assertFalse(HomeRefreshRenderPolicy.shouldKeepRowsForProviderRefresh("", false, false, false))
    }

    @Test
    fun forcesExactlyOnePageRebuild() {
        HomeRefreshRenderPolicy.requestForceRebuild()

        assertTrue(HomeRefreshRenderPolicy.consumeForceRebuild())
        assertFalse(HomeRefreshRenderPolicy.consumeForceRebuild())
    }

    @Test
    fun forcedRebuildCancelsOneTimeRowRetention() {
        HomeRefreshRenderPolicy.requestKeepExistingRows()
        HomeRefreshRenderPolicy.requestForceRebuild()

        assertFalse(HomeRefreshRenderPolicy.consumeKeepExistingRows())
        assertTrue(HomeRefreshRenderPolicy.consumeForceRebuild())
    }

    @Test
    fun clearCancelsPendingPolicies() {
        HomeRefreshRenderPolicy.requestKeepExistingRows()
        HomeRefreshRenderPolicy.requestForceRebuild()
        HomeRefreshRenderPolicy.clear()

        assertFalse(HomeRefreshRenderPolicy.consumeKeepExistingRows())
        assertFalse(HomeRefreshRenderPolicy.consumeForceRebuild())
    }

    // Rendering must follow the actual attached rows, not only retained data equality.
    @Test
    fun unchangedDataDoesNotRebuildAnExistingPage() {
        assertFalse(
            HomeRefreshRenderPolicy.shouldRebuildPage(
                dataUnchanged = true,
                forceRender = false,
                hasRenderedContent = true,
            ),
        )
    }

    @Test
    fun unchangedDataRebuildsWhenRenderedRowsAreMissing() {
        assertTrue(
            HomeRefreshRenderPolicy.shouldRebuildPage(
                dataUnchanged = true,
                forceRender = false,
                hasRenderedContent = false,
            ),
        )
    }

    @Test
    fun changedOrForcedDataAlwaysRebuilds() {
        assertTrue(HomeRefreshRenderPolicy.shouldRebuildPage(false, false, true))
        assertTrue(HomeRefreshRenderPolicy.shouldRebuildPage(true, true, true))
    }
}
