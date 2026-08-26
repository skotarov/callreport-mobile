package com.onlineimoti.calllog

import android.content.Context

/** Keeps every paged list on the incremental, end-of-scroll loading behaviour. */
internal object PageLoadingModeStore {
    private const val PREFS = "relationship_manager_prefs"
    private const val KEY_MODE = "page_loading_mode"

    const val MODE_PREFETCH = "prefetch"
    const val DEFAULT_MODE = MODE_PREFETCH

    fun load(context: Context): String {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, DEFAULT_MODE)
            .orEmpty()
        return normalize(value)
    }

    fun save(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, normalize(mode))
            .apply()
    }

    fun usesPrefetch(context: Context): Boolean = load(context) == MODE_PREFETCH

    /** Migrates the removed button mode to append-at-bottom pagination. */
    internal fun normalize(@Suppress("UNUSED_PARAMETER") value: String): String = MODE_PREFETCH
}
