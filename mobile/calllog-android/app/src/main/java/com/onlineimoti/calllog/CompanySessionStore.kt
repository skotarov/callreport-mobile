package com.onlineimoti.calllog

import android.content.Context
import java.security.MessageDigest

/** Stores the last profile details and binds authenticated actions to the current access token. */
internal object CompanySessionStore {
    private const val PREFS = "relationship_manager_company_session"
    private const val KEY_TOKEN_HASH = "token_hash"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_PHONE = "user_phone"
    private const val KEY_EMAIL_VERIFIED = "email_verified"
    private const val KEY_PHONE_VERIFIED = "phone_verified"
    private const val KEY_ORGANIZATION_NAME = "organization_name"
    private const val KEY_ORGANIZATION_ID = "organization_id"

    data class Snapshot(
        val userName: String,
        val userEmail: String,
        val userPhone: String,
        val emailVerified: Boolean,
        val phoneVerified: Boolean,
        val organizationName: String,
        val organizationId: String,
        val userId: String = "",
    ) {
        val profileReady: Boolean get() = emailVerified || phoneVerified
    }

    fun save(context: Context, session: CompanyAccountApi.Session) {
        val appContext = context.applicationContext
        val saved = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN_HASH, hash(session.accessToken))
            .putString(KEY_USER_NAME, session.userName)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_USER_EMAIL, session.userEmail)
            .putString(KEY_USER_PHONE, session.userPhone)
            .putBoolean(KEY_EMAIL_VERIFIED, session.emailVerified)
            .putBoolean(KEY_PHONE_VERIFIED, session.phoneVerified)
            .putString(KEY_ORGANIZATION_NAME, session.organizationName)
            .putString(KEY_ORGANIZATION_ID, session.organizationId)
            .commit()
        check(saved) { "Профилната сесия не можа да бъде записана на устройството." }
        CrmContactSyncStore.refreshAsync(appContext, force = true)
    }

    fun updateProfile(context: Context, user: CompanyAccountApi.ProfileUser) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USER_NAME, user.name)
            .putString(KEY_USER_ID, user.userId)
            .putString(KEY_USER_EMAIL, user.email)
            .putString(KEY_USER_PHONE, user.phone)
            .putBoolean(KEY_EMAIL_VERIFIED, user.emailVerified)
            .putBoolean(KEY_PHONE_VERIFIED, user.phoneVerified)
            .apply()
    }

    /** Updates only the locally visible name while preserving verified contact data. */
    fun updateUserName(context: Context, userName: String) {
        val safeName = userName.trim()
        if (safeName.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USER_NAME, safeName)
            .commit()
    }

    /** Returns a profile only when the saved access token still owns the session. */
    fun load(context: Context): Snapshot? {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        if (!isCurrent(appContext, config.accessToken)) return null
        return loadStored(appContext)
    }

    /** Returns the last locally remembered profile even after the server token changes or fails. */
    fun loadStored(context: Context): Snapshot? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snapshot = Snapshot(
            userName = prefs.getString(KEY_USER_NAME, "").orEmpty().trim(),
            userEmail = prefs.getString(KEY_USER_EMAIL, "").orEmpty().trim(),
            userPhone = prefs.getString(KEY_USER_PHONE, "").orEmpty().trim(),
            emailVerified = prefs.getBoolean(KEY_EMAIL_VERIFIED, false),
            phoneVerified = prefs.getBoolean(KEY_PHONE_VERIFIED, false),
            organizationName = prefs.getString(KEY_ORGANIZATION_NAME, "").orEmpty().trim(),
            organizationId = prefs.getString(KEY_ORGANIZATION_ID, "").orEmpty().trim(),
            userId = prefs.getString(KEY_USER_ID, "").orEmpty().trim(),
        )
        return snapshot.takeIf {
            it.userId.isNotBlank() || it.userName.isNotBlank() || it.userEmail.isNotBlank() || it.userPhone.isNotBlank()
                || it.organizationName.isNotBlank() || it.organizationId.isNotBlank()
        }
    }

    /**
     * Stable local namespace for profile-owned caches. It intentionally excludes
     * the rotating access token, so the cache survives a new login token.
     */
    fun profileScopeKey(context: Context): String {
        val snapshot = load(context.applicationContext) ?: return ""
        return snapshot.userId.trim()
            .ifBlank { snapshot.userEmail.trim().lowercase() }
            .ifBlank { PhoneNormalizer.key(snapshot.userPhone) }
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
