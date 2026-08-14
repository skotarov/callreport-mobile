package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClientsNoCompanyFilterCompatibilityTest {
    private val config = AppConfig(
        remoteEnabled = true,
        baseUrl = "https://example.test",
        accessToken = "secret-token",
        contactGroups = "",
        notifyUnknownContacts = true,
        notifyKnownContacts = true,
        homeCallPageSize = 20,
        lookupPath = "",
        formPath = "",
        historyPath = "",
        postCallPromptTimeoutSeconds = 30,
        useOverlayPopups = true,
        useCustomStartPopup = true,
        useCustomEndPopup = true,
        postCallEndAction = "",
        contactLinkMode = "",
        showCrmActionButtons = true,
        showBulkContactSyncNotifications = true,
        appLanguage = "bg",
        usePublicNotesFolder = false,
        useCallScreening = false,
        showRmDebugBox = false,
    )

    private fun params(state: HomeCrmFilterState): Map<String, String> =
        ServerCrmContactsQuery.parameters(config, state, "", 20, 0)

    @Test fun noCompanyAndCrmOffOmitsCrmOnlyEntirely() {
        val p = params(HomeCrmFilterState(companyIds = emptySet(), crmOnly = false))
        assertFalse(p.containsKey("company_id"))
        assertFalse(p.containsKey("crm_only"))
    }

    @Test fun noCompanyAndCrmOnStillSendsCrmOnlyOne() {
        val p = params(HomeCrmFilterState(companyIds = emptySet(), crmOnly = true))
        assertFalse(p.containsKey("company_id"))
        assertEquals("1", p["crm_only"])
    }

    @Test fun selectedCompanyAndCrmOffKeepsExistingExplicitZero() {
        val p = params(HomeCrmFilterState(companyIds = setOf("company-7"), crmOnly = false))
        assertEquals("company-7", p["company_id"])
        assertEquals("0", p["crm_only"])
    }

    @Test fun selectedCompanyAndCrmOnKeepsExistingExplicitOne() {
        val p = params(HomeCrmFilterState(companyIds = setOf("company-7"), crmOnly = true))
        assertEquals("company-7", p["company_id"])
        assertEquals("1", p["crm_only"])
    }
}
