package com.onlineimoti.calllog

import android.content.Context

/** Removes only the local profile credential while preserving the user's other app settings. */
internal object ProfileLocalAccessStore {
    private const val PREFS = "relationship_manager_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REMOTE_ENABLED = "remote_enabled"

    fun clear(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val committed = preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .putBoolean(KEY_REMOTE_ENABLED, false)
            .commit()

        check(committed && preferences.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank()) {
            "Локалният access token не можа да бъде изтрит."
        }
    }
}
