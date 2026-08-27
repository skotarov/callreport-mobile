package com.onlineimoti.calllog

import android.content.ContentResolver
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

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
    /** A cursor knows whether its provider applied the requested offset itself. */
    internal class PagedCursor(
        cursor: Cursor,
        val providerPagingApplied: Boolean,
    ) : CursorWrapper(cursor)

    private val legacyPagingUris = ConcurrentHashMap.newKeySet<String>()

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
    ): PagedCursor? {
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)
        val providerKey = uri.toString()
        if (providerKey in legacyPagingUris) {
            return legacyQuery(resolver, uri, projection, selection, selectionArgs, dateColumn, idColumn)
        }
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
        val cursor = resolver.query(uri, projection, queryArgs, null)
        if (cursor != null && pagingArgumentsHonored(
                runCatching {
                    cursor.extras.getStringArray(ContentResolver.EXTRA_HONORED_ARGS)
                }.getOrNull(),
            )
        ) {
            return PagedCursor(cursor, providerPagingApplied = true)
        }
        cursor?.close()
        legacyPagingUris += providerKey
        return legacyQuery(resolver, uri, projection, selection, selectionArgs, dateColumn, idColumn)
    }

    /** Providers that omit any of these can return rows in their own, often old-first order. */
    internal fun pagingArgumentsHonored(honoredArgs: Array<String>?): Boolean {
        val honored = honoredArgs?.toSet() ?: return false
        return honored.containsAll(PAGING_ARGUMENTS)
    }

    private fun legacyQuery(
        resolver: ContentResolver,
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        dateColumn: String,
        idColumn: String,
    ): PagedCursor? = resolver.query(
        uri,
        projection,
        selection,
        selectionArgs,
        "$dateColumn DESC, $idColumn DESC",
    )?.let { PagedCursor(it, providerPagingApplied = false) }

    private val PAGING_ARGUMENTS = setOf(
        ContentResolver.QUERY_ARG_SORT_COLUMNS,
        ContentResolver.QUERY_ARG_SORT_DIRECTION,
        ContentResolver.QUERY_ARG_LIMIT,
        ContentResolver.QUERY_ARG_OFFSET,
    )
}
