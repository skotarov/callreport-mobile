package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigStorePageSizeTest {
    @Test
    fun defaultPageSizeIsTen() {
        assertEquals(10, ConfigStore.DEFAULT_HOME_CALL_PAGE_SIZE)
        assertEquals(10, ConfigStore.migratedHomeCallPageSize(storedValue = null, migrationDone = false))
    }

    @Test
    fun oldDefaultTwentyMigratesOnceToTen() {
        assertEquals(10, ConfigStore.migratedHomeCallPageSize(storedValue = 20, migrationDone = false))
        assertEquals(20, ConfigStore.migratedHomeCallPageSize(storedValue = 20, migrationDone = true))
    }

    @Test
    fun explicitCustomPageSizeIsPreserved() {
        assertEquals(15, ConfigStore.migratedHomeCallPageSize(storedValue = 15, migrationDone = false))
    }
}
