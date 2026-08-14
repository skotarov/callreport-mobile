package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactServerCompanyScopePolicyTest {
    @Test
    fun allowsUnknownNumberWithoutCrmMembership() {
        assertTrue(ContactServerCompanyScopePolicy.isAvailable(
            crmEnabled = false,
            unknownNumber = true,
        ))
    }

    @Test
    fun allowsCrmContact() {
        assertTrue(ContactServerCompanyScopePolicy.isAvailable(
            crmEnabled = true,
            unknownNumber = false,
        ))
    }

    @Test
    fun knownNonCrmContactStaysUnavailableWithoutExplicitCompanyNoteIntent() {
        assertFalse(ContactServerCompanyScopePolicy.isAvailable(
            crmEnabled = false,
            unknownNumber = false,
        ))
    }

    @Test
    fun explicitCompanyNoteAllowsKnownNonCrmContact() {
        assertTrue(ContactServerCompanyScopePolicy.isAvailable(
            crmEnabled = false,
            unknownNumber = false,
            explicitCompanyNotesAllowed = true,
        ))
    }
}
