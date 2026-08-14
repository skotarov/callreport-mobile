package com.onlineimoti.calllog

import android.content.Context

/**
 * Privacy boundary for communication metadata.
 *
 * Unknown numbers are company-visible because they are not part of the user's
 * personal address book. Known contacts stay private unless the user has an
 * active CRM/care marker. A company-scoped note is synced by its own outbox and
 * carries the exact call context without turning future personal communication
 * into company-visible data.
 */
internal object CommunicationSyncPrivacyPolicy {
    fun shouldShare(context: Context, phone: String): Boolean {
        if (phone.isBlank()) return false
        if (CrmContactSyncStore.isEnabled(context, phone)) return true
        return ContactServerCompanyScope.isUnknownNumber(context, phone)
    }

    internal fun shouldShare(crmEnabled: Boolean, unknownNumber: Boolean): Boolean =
        crmEnabled || unknownNumber
}
