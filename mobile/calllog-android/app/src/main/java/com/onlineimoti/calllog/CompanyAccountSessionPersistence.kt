package com.onlineimoti.calllog

import android.content.Context

/** Persists authenticated profile access across token rotation and membership changes. */
internal object CompanyAccountSessionPersistence {
    fun apply(context: Context, session: CompanyAccountApi.Session) {
        val appContext = context.applicationContext
        val current = ConfigStore.load(appContext)
        val serverUrl = current.baseUrl.trim()
        val incomingToken = session.accessToken.trim()
        require(serverUrl.isNotBlank()) { "Липсва сървърен URL." }
        require(incomingToken.isNotBlank()) { "Липсва access token." }

        val mergedSession = mergeRememberedProfile(
            CompanySessionStore.loadStored(appContext),
            session.copy(accessToken = incomingToken),
        )
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
            CompanySessionStore.updateProfile(appContext, mergedSession.user())
            CrmContactSyncStore.refreshAsync(appContext, force = true)
        } else {
            CompanySessionStore.save(appContext, mergedSession)
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

    private fun mergeRememberedProfile(
        remembered: CompanySessionStore.Snapshot?,
        incoming: CompanyAccountApi.Session,
    ): CompanyAccountApi.Session {
        if (remembered == null) return incoming

        val email = incoming.userEmail.trim().ifBlank { remembered.userEmail }
        val phone = incoming.userPhone.trim().ifBlank { remembered.userPhone }
        val emailMatches = email.isNotBlank() &&
            remembered.userEmail.trim().equals(email, ignoreCase = true)
        val incomingPhoneKey = PhoneNormalizer.key(phone)
        val phoneMatches = incomingPhoneKey.isNotBlank() &&
            PhoneNormalizer.key(remembered.userPhone) == incomingPhoneKey

        return incoming.copy(
            userName = incoming.userName.trim().ifBlank { remembered.userName },
            userEmail = email,
            userPhone = phone,
            emailVerified = incoming.emailVerified || (emailMatches && remembered.emailVerified),
            phoneVerified = incoming.phoneVerified || (phoneMatches && remembered.phoneVerified),
            organizationName = incoming.organizationName.trim().ifBlank { remembered.organizationName },
            organizationId = incoming.organizationId.trim().ifBlank { remembered.organizationId },
        )
    }
}
