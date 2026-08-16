package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNoteTopicRenderPolicyTest {
    private val company = CallReportTopicCompany(
        id = "company-1",
        name = "Company One",
        role = "member",
        updatedAtMs = 100L,
    )

    @Test fun matchingLiveRefreshDoesNotRebuildCachedCompanyFields() {
        val cached = ContactNoteTopicState(
            visible = true,
            loading = true,
            companies = listOf(company),
            includeLocalOption = true,
            usingCachedCompanies = true,
            cachedCompaniesUpdatedAtMs = 50L,
        )
        val live = cached.copy(
            loading = false,
            usingCachedCompanies = false,
            cachedCompaniesUpdatedAtMs = 0L,
        )

        assertFalse(ContactNoteTopicRenderPolicy.shouldRebind(cached, live, scopeValuesChanged = false))
    }

    @Test fun changedCompanyFromLiveRefreshRebuildsFields() {
        val cached = ContactNoteTopicState(
            visible = true,
            loading = true,
            companies = listOf(company),
            includeLocalOption = true,
            usingCachedCompanies = true,
        )
        val live = cached.copy(
            loading = false,
            companies = listOf(company.copy(name = "Company One Updated", updatedAtMs = 200L)),
            usingCachedCompanies = false,
        )

        assertTrue(ContactNoteTopicRenderPolicy.shouldRebind(cached, live, scopeValuesChanged = false))
    }

    @Test fun changedServerNoteTextRebuildsFieldsEvenWhenCompaniesMatch() {
        val state = ContactNoteTopicState(
            visible = true,
            companies = listOf(company),
            includeLocalOption = true,
        )

        assertTrue(ContactNoteTopicRenderPolicy.shouldRebind(state, state, scopeValuesChanged = true))
    }

    @Test fun offlineFallbackAddsVisibleStatusAndRebuildsOnce() {
        val refreshing = ContactNoteTopicState(
            visible = true,
            loading = true,
            companies = listOf(company),
            includeLocalOption = true,
            usingCachedCompanies = true,
        )
        val offline = refreshing.copy(loading = false)

        assertTrue(ContactNoteTopicRenderPolicy.shouldRebind(refreshing, offline, scopeValuesChanged = false))
    }
}
