package com.onlineimoti.calllog

/** Exact source window for one automatic Call Log page. */
internal object HomeTimelinePageWindow {
    fun sourceRowsForPage(pageIndex: Int, pageSize: Int): Int =
        ((pageIndex.coerceAtLeast(0) + 1).toLong() * pageSize.coerceIn(5, 100).toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    fun <T> rowsForPage(rows: List<T>, pageIndex: Int, pageSize: Int): List<T> {
        val safePage = pageIndex.coerceAtLeast(0)
        val safeSize = pageSize.coerceIn(5, 100)
        val offset = (safePage.toLong() * safeSize.toLong())
            .coerceAtMost(rows.size.toLong())
            .toInt()
        return rows.drop(offset).take(safeSize)
    }
}
