package com.onlineimoti.calllog

import android.content.Context

/**
 * CRM Mode is a Home-only filter. It is available only while Cloud sync is
 * enabled in Settings, and never starts a server request by itself.
 */
internal object HomeCrmModeStore {
    private const val PREFS = "relationship_manager_prefs"
    private const val KEY_ENABLED = "home_crm_mode_enabled"

    fun isAvailable(context: Context): Boolean =
        CallReportRemoteAccess.isEnabled(context.applicationContext)

    fun isEnabled(context: Context): Boolean {
        if (!isAvailable(context)) return false
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        // This flag controls only the visible Home filter "CRM calls". Settings may
        // make CRM features available by enabling the server, but must never switch
        // the user's current Home timeline into the filtered CRM-calls view.
        if (enabled && context !is HomeActivity) return false
        if (enabled && !isAvailable(context)) return false
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        return true
    }
}
