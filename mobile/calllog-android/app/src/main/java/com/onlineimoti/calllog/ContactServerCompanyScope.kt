package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Determines whether a phone may use the server-backed company-note scope.
 *
 * Every real phone may explicitly receive a company-scoped note. Choosing a
 * company is an intentional company action for that exact note/call and must not
 * silently turn future communication with a known personal contact into shared
 * company communication.
 */
internal object ContactServerCompanyScope {
    fun isAvailable(context: Context, phone: String): Boolean {
        if (phone.isBlank()) return false
        return ContactServerCompanyScopePolicy.isAvailable(
            crmEnabled = CrmContactSyncStore.isEnabled(context, phone),
            unknownNumber = isUnknownNumber(context, phone),
            explicitCompanyNotesAllowed = true,
        )
    }

    fun isUnknownNumber(context: Context, phone: String): Boolean {
        if (phone.isBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            // Privacy fail-safe: without Contacts access we cannot prove that a
            // number is unknown to this user, so never share it on that assumption.
            // Explicit CRM/care markers are checked separately by the sync policy.
            return false
        }
        return RmRealContactLookup.findContactId(context, phone) <= 0L
    }
}

internal object ContactServerCompanyScopePolicy {
    fun isAvailable(
        crmEnabled: Boolean,
        unknownNumber: Boolean,
        explicitCompanyNotesAllowed: Boolean = false,
    ): Boolean = crmEnabled || unknownNumber || explicitCompanyNotesAllowed
}
