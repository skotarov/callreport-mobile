package com.onlineimoti.calllog

import android.content.Context

/** Persists authenticated profile access without treating company membership changes as a new login. */
internal object CompanyAccountSessionPersistence {
    fun apply(context: Context, session: CompanyAccountApi.Session) {
        val appContext = context.applicationContext
        val current = ConfigStore.load(appContext)
        val serverUrl = current.baseUrl.trim()
        val incomingToken = session.accessToken.trim()
        require(serverUrl.isNotBlank()) { "Липсва сървърен URL." }
        require(incomingToken.isNotBlank()) { "Липсва access token." }

        val sameAuthenticatedSession = current.accessToken.trim() == incomingToken &&
            CompanySessionStore.isCurrent(appContext, incomingToken)

        ConfigStore.save(
            appContext,
            current.copy(
                remoteEnabled = true,
                baseUrl = serverUrl,
                accessToken = incomingToken,
            ),
        )

        if (sameAuthenticatedSession) {
            CompanySessionStore.updateProfile(appContext, session.user())
            CrmContactSyncStore.refreshAsync(appContext, force = true)
        } else {
            CompanySessionStore.save(appContext, session)
        }
    }

    fun updateProfile(context: Context, user: CompanyAccountApi.ProfileUser) {
        CompanySessionStore.updateProfile(context, user)
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        ProfileLocalAccessStore.clear(appContext)
        CompanySessionStore.clear(appContext)
    }
}
