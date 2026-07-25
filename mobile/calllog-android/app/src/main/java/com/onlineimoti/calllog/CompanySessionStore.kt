package com.onlineimoti.calllog

import android.content.Context
import java.security.MessageDigest

/** Stores evidence that the configured device credential came from a company login flow. */
internal object CompanySessionStore {
    private const val PREFS = "relationship_manager_company_session"
    private const val KEY_TOKEN_HASH = "token_hash"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_ORGANIZATION_NAME = "organization_name"
    private const val KEY_ORGANIZATION_ID = "organization_id"

    data class Snapshot(
        val userName: String,
        val organizationName: String,
        val organizationId: String,
    )

    fun save(context: Context, session: CompanyAccountApi.Session) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN_HASH, hash(session.accessToken))
            .putString(KEY_USER_NAME, session.userName)
            .putString(KEY_ORGANIZATION_NAME, session.organizationName)
            .putString(KEY_ORGANIZATION_ID, session.organizationId)
            .apply()
    }

    fun load(context: Context): Snapshot? {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        if (!isCurrent(appContext, config.accessToken)) return null
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snapshot = Snapshot(
            userName = prefs.getString(KEY_USER_NAME, "").orEmpty().trim(),
            organizationName = prefs.getString(KEY_ORGANIZATION_NAME, "").orEmpty().trim(),
            organizationId = prefs.getString(KEY_ORGANIZATION_ID, "").orEmpty().trim(),
        )
        return snapshot.takeIf {
            it.userName.isNotBlank() || it.organizationName.isNotBlank() || it.organizationId.isNotBlank()
        }
    }

    fun isCurrent(context: Context, accessToken: String): Boolean {
        if (accessToken.isBlank()) return false
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN_HASH, "")
            .orEmpty()
        return stored.isNotBlank() && stored == hash(accessToken)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun hash(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
