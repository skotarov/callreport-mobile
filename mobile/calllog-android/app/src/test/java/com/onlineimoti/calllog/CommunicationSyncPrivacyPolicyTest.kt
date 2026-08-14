package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunicationSyncPrivacyPolicyTest {
    @Test
    fun unknownNumberIsCompanyVisible() {
        assertTrue(CommunicationSyncPrivacyPolicy.shouldShare(
            crmEnabled = false,
            unknownNumber = true,
        ))
    }

    @Test
    fun crmMarkedKnownContactIsCompanyVisible() {
        assertTrue(CommunicationSyncPrivacyPolicy.shouldShare(
            crmEnabled = true,
            unknownNumber = false,
        ))
    }

    @Test
    fun ordinaryKnownPersonalContactStaysPrivate() {
        assertFalse(CommunicationSyncPrivacyPolicy.shouldShare(
            crmEnabled = false,
            unknownNumber = false,
        ))
    }

    @Test
    fun companyNoteSharesOnlyItsExactKnownPersonalCall() {
        assertTrue(CommunicationSyncPrivacyPolicy.shouldShareCall(
            crmEnabled = false,
            unknownNumber = false,
            exactCompanyCall = true,
        ))
        assertFalse(CommunicationSyncPrivacyPolicy.shouldShareCall(
            crmEnabled = false,
            unknownNumber = false,
            exactCompanyCall = false,
        ))
    }
}
