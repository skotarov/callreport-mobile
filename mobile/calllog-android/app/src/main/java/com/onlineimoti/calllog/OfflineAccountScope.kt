package com.onlineimoti.calllog

import android.content.Context
import java.security.MessageDigest

/** Stable profile/server identity for durable work; never stores the access token. */
internal object OfflineAccountScope {
    fun current(context: Context): String {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        val profile = CompanySessionStore.profileScopeKey(appContext)
        if (baseUrl.isBlank() || profile.isBlank()) return ""
        return sha256("$baseUrl|$profile")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
