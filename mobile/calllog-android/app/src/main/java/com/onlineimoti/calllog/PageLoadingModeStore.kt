package com.onlineimoti.calllog

import android.content.Context

/** Controls whether paged timelines advance only by buttons or preload while scrolling. */
internal object PageLoadingModeStore {
    private const val PREFS = "relationship_manager_prefs"
    private const val KEY_MODE = "page_loading_mode"

    const val MODE_BUTTONS = "buttons"
    const val MODE_PREFETCH = "prefetch"
    // Automatic expansion can turn one busy day into a very large rendered page.
    // Existing user choices are retained; new installs start with predictable,
    // bounded button pages and may opt in to prefetch from Settings.
    const val DEFAULT_MODE = MODE_BUTTONS

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

    private fun normalize(value: String): String = when (value.trim()) {
        MODE_BUTTONS -> MODE_BUTTONS
        else -> MODE_PREFETCH
    }
}
