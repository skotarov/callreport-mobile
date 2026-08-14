package com.onlineimoti.calllog

/**
 * Decides whether a server page is authoritative enough to stop Clients compatibility fallback.
 *
 * For an explicit company scope, even an empty page is authoritative. For the neutral no-company
 * scope, only a non-empty payload is authoritative; metadata such as total/count must not suppress
 * fallback because mixed/legacy deployments can report a non-zero total while returning no rows.
 */
internal object ClientsPrimaryPagePolicy {
    fun shouldAccept(rowCount: Int, hasCompanyFilter: Boolean): Boolean =
        hasCompanyFilter || rowCount > 0
}
