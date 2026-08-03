package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallReportAuthorIdentityPolicyTest {
    @Test
    fun sameProfileIdStaysMineAfterNameChange() {
        val principal = CallReportHistoryPrincipal(profileId = "profile-12", brokerName = "Ново име")
        val event = CallReportHistoryEvent(authorProfileId = "profile-12", authorBrokerName = "Старо име")
        assertFalse(CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal))
        assertTrue(CallReportAuthorIdentityPolicy.canEdit(event, principal))
    }

    @Test
    fun differentProfileIdWinsOverMatchingName() {
        val principal = CallReportHistoryPrincipal(profileId = "profile-12", brokerName = "Светослав")
        val event = CallReportHistoryEvent(authorProfileId = "profile-99", authorBrokerName = "Светослав")
        assertTrue(CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal))
        assertFalse(CallReportAuthorIdentityPolicy.canEdit(event, principal))
    }

    @Test
    fun authoritativeMineFlagWinsOverLegacyNamespaceMismatch() {
        val principal = CallReportHistoryPrincipal(profileId = "profile-12", brokerId = "employee-5")
        val event = CallReportHistoryEvent(authorProfileId = "legacy", authorBrokerId = "legacy", isMine = true, canEdit = true)
        assertFalse(CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal))
        assertTrue(CallReportAuthorIdentityPolicy.canEdit(event, principal))
    }

    @Test
    fun brokerIdIsASeparateLegacyFallback() {
        val principal = CallReportHistoryPrincipal(brokerId = "employee-5", brokerName = "Ново име")
        assertFalse(CallReportAuthorIdentityPolicy.isOtherAuthor(CallReportHistoryEvent(authorBrokerId = "employee-5", authorBrokerName = "Старо име"), principal))
        assertTrue(CallReportAuthorIdentityPolicy.isOtherAuthor(CallReportHistoryEvent(authorBrokerId = "employee-8", authorBrokerName = "Ново име"), principal))
    }
}
