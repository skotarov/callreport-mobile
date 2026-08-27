package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

/** Keeps the default at 20 without overriding a value explicitly saved in Settings. */
class ConfigStorePageSizeTest {
    @Test
    fun defaultPageSizeIsTwenty() {
        assertEquals(20, ConfigStore.DEFAULT_HOME_CALL_PAGE_SIZE)
        assertEquals(20, ConfigStore.normalizedHomeCallPageSize(storedValue = null))
    }

    @Test
    fun savedPageSizeIsPreserved() {
        assertEquals(20, ConfigStore.normalizedHomeCallPageSize(storedValue = 20))
    }

    @Test
    fun explicitCustomPageSizeIsPreserved() {
        assertEquals(15, ConfigStore.normalizedHomeCallPageSize(storedValue = 15))
    }
}
