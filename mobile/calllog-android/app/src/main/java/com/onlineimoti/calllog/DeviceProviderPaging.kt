package com.onlineimoti.calllog

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Requests one bounded, stable page directly from a device ContentProvider.
 *
 * The callers used to query the complete Call Log/SMS cursor and apply the
 * requested offset while iterating it.  Apart from being slow on long logs,
 * that made the visible page depend on how much data the provider had already
 * handed to the app.  Android 8+ exposes standard query arguments for this;
 * this app has a minSdk above that version.
 */
internal object DeviceProviderPaging {
    fun query(
        resolver: ContentResolver,
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        dateColumn: String,
        idColumn: String,
        limit: Int,
        offset: Int = 0,
    ): Cursor? {
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)
        val queryArgs = Bundle().apply {
            if (!selection.isNullOrBlank()) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            }
            if (!selectionArgs.isNullOrEmpty()) {
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            }
            // Date alone is not a stable page boundary: multiple calls/SMS can
            // have the same millisecond. The provider ID keeps pages disjoint.
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(dateColumn, idColumn))
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, safeLimit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, safeOffset)
        }
        return resolver.query(uri, projection, queryArgs, null)
    }
}
