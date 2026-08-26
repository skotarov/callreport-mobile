package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class PageLoadingModeStoreTest {
    @Test
    fun legacyButtonPreferenceMigratesToAppendAtBottomPaging() {
        assertEquals(
            PageLoadingModeStore.MODE_PREFETCH,
            PageLoadingModeStore.normalize("buttons"),
        )
    }
}
