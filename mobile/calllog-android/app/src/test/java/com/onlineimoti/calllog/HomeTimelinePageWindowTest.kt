package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTimelinePageWindowTest {
    @Test
    fun `first automatic page reads only one configured page from each provider`() {
        assertEquals(20, HomeTimelinePageWindow.sourceRowsForPage(pageIndex = 0, pageSize = 20))
    }

    @Test
    fun `next automatic page appends the next configured range`() {
        val rows = (0 until 60).toList()

        assertEquals((20 until 40).toList(), HomeTimelinePageWindow.rowsForPage(rows, pageIndex = 1, pageSize = 20))
        assertEquals(40, HomeTimelinePageWindow.sourceRowsForPage(pageIndex = 1, pageSize = 20))
    }
}
