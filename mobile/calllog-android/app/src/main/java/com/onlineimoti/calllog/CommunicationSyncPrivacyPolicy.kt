package com.onlineimoti.calllog

import android.content.Context

/**
 * Privacy boundary for communication metadata.
 *
 * Unknown numbers are company-visible because they are not part of the user's
 * personal address book. Known contacts stay private unless the user has an
 * active CRM/care marker. One exact call may also be company-visible when the
 * user explicitly attached a company-scoped note to that call.
 */
internal object CommunicationSyncPrivacyPolicy {
    fun shouldShare(context: Context, phone: String): Boolean {
        if (phone.isBlank()) return false
        if (CrmContactSyncStore.isEnabled(context, phone)) return true
        return ContactServerCompanyScope.isUnknownNumber(context, phone)
    }

    fun shouldShareCall(
        context: Context,
        phone: String,
        direction: String,
        occurredAtMs: Long,
    ): Boolean = shouldShareCall(
        crmEnabled = CrmContactSyncStore.isEnabled(context, phone),
        unknownNumber = ContactServerCompanyScope.isUnknownNumber(context, phone),
        exactCompanyCall = CompanySharedCallStore.isMarked(context, phone, direction, occurredAtMs),
    )

    internal fun shouldShare(crmEnabled: Boolean, unknownNumber: Boolean): Boolean =
        crmEnabled || unknownNumber

    internal fun shouldShareCall(
        crmEnabled: Boolean,
        unknownNumber: Boolean,
        exactCompanyCall: Boolean,
    ): Boolean = crmEnabled || unknownNumber || exactCompanyCall
}
