package com.onlineimoti.calllog

/** Rendering hints used while refreshing an already visible Call Log. */
internal object HomeRefreshRenderPolicy {
    private var keepExistingRowsOnce = false
    private var keepExistingRowsHeld = false
    private var forceRebuildOnce = false

    @Synchronized
    fun requestKeepExistingRows() {
        keepExistingRowsOnce = true
    }

    /** Rebuilds the page even when the loaded data equals the retained in-memory model. */
    @Synchronized
    fun requestForceRebuild() {
        forceRebuildOnce = true
        keepExistingRowsOnce = false
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
    fun clear() {
        keepExistingRowsOnce = false
        keepExistingRowsHeld = false
        forceRebuildOnce = false
    }
}
