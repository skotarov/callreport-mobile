package com.onlineimoti.calllog

/** Rendering hints used while refreshing an already visible Call Log. */
internal object HomeRefreshRenderPolicy {
    private var keepExistingRowsOnce = false
    private var keepExistingRowsHeld = false
    private var forceRebuildOnce = false
    private var bypassClientsCacheOnce = false

    @Synchronized
    fun requestKeepExistingRows() {
        keepExistingRowsOnce = true
    }

    /** A provider notification is a background recheck, never a reason to blank an open Call Log. */
    fun shouldKeepRowsForProviderRefresh(
        activeSearchQuery: String,
        crmCallLogEnabled: Boolean,
        crmContactsMode: Boolean,
        hasRenderedRows: Boolean,
    ): Boolean = activeSearchQuery.isBlank() &&
        !crmCallLogEnabled &&
        !crmContactsMode &&
        hasRenderedRows

    /** Rebuilds the page even when the loaded data equals the retained in-memory model. */
    @Synchronized
    fun requestForceRebuild() {
        forceRebuildOnce = true
        keepExistingRowsOnce = false
    }

    /** A manual Clients pull must wait for a fresh server response instead of rendering stale SQLite rows first. */
    @Synchronized
    fun requestBypassClientsCache() {
        bypassClientsCacheOnce = true
    }

    /** Keeps every refresh non-destructive until the matching screen flow is finished. */
    @Synchronized
    fun holdExistingRows() {
        keepExistingRowsHeld = true
    }

    @Synchronized
    fun releaseHeldRows() {
        keepExistingRowsHeld = false
    }

    @Synchronized
    fun consumeKeepExistingRows(): Boolean {
        val requested = keepExistingRowsOnce || keepExistingRowsHeld
        keepExistingRowsOnce = false
        return requested
    }

    @Synchronized
    fun consumeForceRebuild(): Boolean {
        val requested = forceRebuildOnce
        forceRebuildOnce = false
        return requested
    }

    @Synchronized
    fun consumeBypassClientsCache(): Boolean {
        val requested = bypassClientsCacheOnce
        bypassClientsCacheOnce = false
        return requested
    }

    /** Same data still needs a rebuild when Android no longer has rendered rows. */
    fun shouldRebuildPage(
        dataUnchanged: Boolean,
        forceRender: Boolean,
        hasRenderedContent: Boolean,
    ): Boolean = forceRender || !dataUnchanged || !hasRenderedContent

    @Synchronized
    fun clear() {
        keepExistingRowsOnce = false
        keepExistingRowsHeld = false
        forceRebuildOnce = false
        bypassClientsCacheOnce = false
    }
}
