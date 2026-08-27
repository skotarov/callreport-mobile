package com.onlineimoti.calllog

import android.content.ContentResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProviderPagingTest {
    @Test
    fun providerWithoutHonoredPagingArgumentsUsesLegacyOrderingFallback() {
        assertFalse(
            DeviceProviderPaging.pagingArgumentsHonored(
                arrayOf(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ),
            ),
        )
    }

    @Test
    fun providerWithAllPagingArgumentsKeepsBoundedQuery() {
        assertTrue(
            DeviceProviderPaging.pagingArgumentsHonored(
                arrayOf(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_ARG_LIMIT,
                    ContentResolver.QUERY_ARG_OFFSET,
                ),
            ),
        )
    }
}
