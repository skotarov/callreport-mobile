package com.onlineimoti.calllog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientsServerContractTest {
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

    private fun params(
        state: HomeCrmFilterState = HomeCrmFilterState(),
        q: String = "",
        limit: Int = 20,
        offset: Int = 0,
    ) = ServerCrmContactsQuery.parameters(config, state, q, limit, offset)

    @Test fun noFiltersLeavesOptionalDimensionsOut() {
        val p = params()
        assertFalse(p.containsKey("crm_only")); assertFalse(p.containsKey("company_id")); assertFalse(p.containsKey("phase"))
    }

    @Test fun crmOffMeansNoCrmRestriction() = assertFalse(params(HomeCrmFilterState(crmOnly = false)).containsKey("crm_only"))
    @Test fun crmOnMeansCurrentUserCrmRestriction() = assertEquals("1", params(HomeCrmFilterState(crmOnly = true))["crm_only"])
    @Test fun companyEmptyMeansNoCompanyRestriction() = assertFalse(params(HomeCrmFilterState()).containsKey("company_id"))
    @Test fun companySelectionIsSentServerSide() = assertEquals("a,b", params(HomeCrmFilterState(companyIds = setOf("b", "a")))["company_id"])
    @Test fun phaseEmptyMeansNoPhaseRestriction() = assertFalse(params(HomeCrmFilterState()).containsKey("phase"))
    @Test fun phaseSelectionIsSentServerSide() = assertEquals("2,4", params(HomeCrmFilterState(phases = setOf(4, 2)))["phase"])

    @Test fun searchAndCrmAreCombined() {
        val p = params(HomeCrmFilterState(crmOnly = true), "ivan")
        assertEquals("ivan", p["q"]); assertEquals("1", p["crm_only"])
    }

    @Test fun searchAndCompanyAreCombined() {
        val p = params(HomeCrmFilterState(companyIds = setOf("7")), "ivan")
        assertEquals("ivan", p["q"]); assertEquals("7", p["company_id"])
    }

    @Test fun searchAndPhaseAreCombined() {
        val p = params(HomeCrmFilterState(phases = setOf(3)), "ivan")
        assertEquals("ivan", p["q"]); assertEquals("3", p["phase"])
    }

    @Test fun searchCombinesAllFilters() {
        val p = params(HomeCrmFilterState(setOf(2), setOf("9"), true), "note")
        assertEquals("note", p["q"]); assertEquals("1", p["crm_only"]); assertEquals("9", p["company_id"]); assertEquals("2", p["phase"])
    }

    @Test fun pageZeroUsesOffsetZero() { val p = params(limit = 20, offset = 0); assertEquals("20", p["limit"]); assertEquals("0", p["offset"]) }
    @Test fun pageOneUsesOffsetTwenty() { val p = params(limit = 20, offset = 20); assertEquals("20", p["limit"]); assertEquals("20", p["offset"]) }
    @Test fun pageTwoUsesOffsetForty() = assertEquals("40", params(offset = 40)["offset"])
    @Test fun limitIsNeverTheOldTwoHundredWindow() = assertEquals("20", params()["limit"])
    @Test fun negativeOffsetIsClamped() = assertEquals("0", params(offset = -20)["offset"])
    @Test fun oversizedLimitIsClamped() = assertEquals("100", params(limit = 500)["limit"])
    @Test fun searchIsTrimmed() = assertEquals("abc", params(q = "  abc  ")["q"])
    @Test fun searchLegacyAliasMatchesCanonicalQuery() { val p = params(q = "abc"); assertEquals(p["q"], p["search"]) }
    @Test fun phaseLegacyAliasMatchesCanonicalPhase() { val p = params(HomeCrmFilterState(phases = setOf(1))); assertEquals(p["phase"], p["phases"]) }

    @Test fun serverPageRemainsAuthoritativeAfterFilteringRequest() {
        val json = JSONObject(
            """{
                "ok": true,
                "total": 1,
                "limit": 20,
                "offset": 0,
                "contacts": [
                    {"client_id":"c1","phone":"0888123456","is_crm":false,"phase":1}
                ]
            }""".trimIndent(),
        )
        val page = ServerCrmContactsClient.parsePage(
            json = json,
            filterState = HomeCrmFilterState(phases = setOf(4), crmOnly = true),
            requestedLimit = 20,
            requestedOffset = 0,
        )
        assertEquals(1, page.total)
        assertEquals(1, page.clients.size)
        assertEquals("c1", page.clients.single().identity)
        assertFalse(page.clients.single().isCrm == true)
        assertEquals(1, page.clients.single().phase)
    }

    @Test fun malformedBlankPhoneIsStillRejectedWithoutChangingServerTotal() {
        val json = JSONObject(
            """{"ok":true,"total":2,"limit":20,"offset":0,"contacts":[{"client_id":"bad","phone":""},{"client_id":"good","phone":"0888123456"}]}""",
        )
        val page = ServerCrmContactsClient.parsePage(json, HomeCrmFilterState(), 20, 0)
        assertEquals(2, page.total)
        assertEquals(1, page.clients.size)
        assertEquals("good", page.clients.single().identity)
    }

    @Test fun newerCrmTimestampWins() {
        val old = ClientsObjectMerge.CrmState(true, 10)
        val fresh = ClientsObjectMerge.CrmState(false, 20)
        assertEquals(fresh, ClientsObjectMerge.crm(old, fresh))
    }

    @Test fun olderCrmTimestampCannotOverwrite() {
        val local = ClientsObjectMerge.CrmState(false, 20)
        assertEquals(local, ClientsObjectMerge.crm(local, ClientsObjectMerge.CrmState(true, 10)))
    }

    @Test fun missingCrmTimestampCannotOverwrite() {
        val local = ClientsObjectMerge.CrmState(true, 20)
        assertEquals(local, ClientsObjectMerge.crm(local, ClientsObjectMerge.CrmState(false, 0)))
    }

    @Test fun newerPhaseTimestampWinsIndependently() {
        val fresh = ClientsObjectMerge.PhaseState(4, 30)
        assertEquals(fresh, ClientsObjectMerge.phase(ClientsObjectMerge.PhaseState(1, 20), fresh))
    }

    @Test fun olderPhaseCannotOverwriteCurrentUserPhase() {
        val local = ClientsObjectMerge.PhaseState(2, 30)
        assertEquals(local, ClientsObjectMerge.phase(local, ClientsObjectMerge.PhaseState(4, 20)))
    }

    @Test fun crmAndPhaseClocksAreIndependent() {
        val crm = ClientsObjectMerge.crm(ClientsObjectMerge.CrmState(true, 100), ClientsObjectMerge.CrmState(false, 50))
        val phase = ClientsObjectMerge.phase(ClientsObjectMerge.PhaseState(1, 50), ClientsObjectMerge.PhaseState(4, 100))
        assertTrue(crm.active == true); assertEquals(4, phase.phase)
    }

    @Test fun differentUsersAreKeyedIndependentlyByModel() {
        val a = ServerCrmUserState("a", "A", true, 10, 1, 11)
        val b = ServerCrmUserState("b", "B", false, 12, 4, 13)
        assertFalse(a.userId == b.userId); assertEquals(1, a.phase); assertEquals(4, b.phase)
    }

    @Test fun noteMergeUsesItsOwnTimestamp() {
        assertFalse(ClientsObjectMerge.noteUpdatedAt(30, 20)); assertTrue(ClientsObjectMerge.noteUpdatedAt(30, 30)); assertTrue(ClientsObjectMerge.noteUpdatedAt(30, 40))
    }

    @Test fun noteOwnershipMetadataIsIndependent() {
        val own = ServerCrmNote("1", "me", "Аз", "", "x", 1, 1, true)
        val other = ServerCrmNote("2", "u2", "Иван", "", "y", 1, 1, false)
        assertTrue(own.editable); assertFalse(other.editable); assertEquals("Иван", other.authorName)
    }

    @Test fun stableIdentityFallsBackToNormalizedPhoneModel() {
        val client = ServerCrmClient("359888123456", "+359 888 123 456", "359888123456", "", 0, null, 0, null, 0, emptySet(), emptyList(), emptyList(), "")
        assertEquals(client.normalizedPhone, client.identity)
    }

    @Test fun legacyCurrentUserFieldsRemainNullable() {
        val client = ServerCrmClient("k", "0888", "0888", "", 0, null, 0, null, 0, emptySet(), emptyList(), emptyList(), "")
        assertNull(client.isCrm); assertNull(client.phase)
    }
}
