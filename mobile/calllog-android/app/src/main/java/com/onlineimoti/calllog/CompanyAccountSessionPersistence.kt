package com.onlineimoti.calllog

import android.content.Context

/** Persists the verified server URL, rotating token and local profile snapshot together. */
internal object CompanyAccountSessionPersistence {
    fun apply(context: Context, session: CompanyAccountApi.Session) {
        val current = ConfigStore.load(context)
        val serverUrl = current.baseUrl.trim()
        require(serverUrl.isNotBlank()) { "Липсва сървърен URL." }
        require(session.accessToken.isNotBlank()) { "Липсва access token." }
        ConfigStore.save(
            context,
            current.copy(
                remoteEnabled = true,
                baseUrl = serverUrl,
                accessToken = session.accessToken,
            ),
        )
        CompanySessionStore.save(context, session)
    }

    fun updateProfile(context: Context, user: CompanyAccountApi.ProfileUser) {
        CompanySessionStore.updateProfile(context, user)
    }

    fun clear(context: Context) {
        val current = ConfigStore.load(context)
        ConfigStore.save(
            context,
            current.copy(
                remoteEnabled = false,
                accessToken = "",
            ),
        )
        CompanySessionStore.clear(context)
    }
}
