package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNoteScopeFieldLoadPolicyTest {
    private val loadingState = ContactNoteTopicState(
        visible = true,
        loading = true,
        companies = listOf(
            CallReportTopicCompany(
                id = "company-1",
                name = "Фирма 1",
                role = "member",
                updatedAtMs = 100L,
            ),
        ),
        includeLocalOption = true,
    )

    @Test
    fun blankServerFieldStaysDisabledWhileLoadingAuthoritativeValue() {
        val field = ContactNoteScopeFieldLoadPolicy.resolve(
            companyId = "company-1",
            topicState = loadingState,
            text = "",
            hasPersistedValue = false,
        )

        assertFalse(field.editable)
        assertTrue(field.helperText.isNotBlank())
    }

    @Test
    fun localFieldRemainsEditableWhileCompaniesLoad() {
        val field = ContactNoteScopeFieldLoadPolicy.resolve(
            companyId = ContactNoteTopicState.LOCAL_COMPANY_ID,
            topicState = loadingState,
            text = "",
            hasPersistedValue = true,
        )

        assertTrue(field.editable)
        assertEquals("", field.helperText)
    }

    @Test
    fun cachedServerTextRemainsEditableWhileRefreshRuns() {
        val field = ContactNoteScopeFieldLoadPolicy.resolve(
            companyId = "company-1",
            topicState = loadingState,
            text = "Кеширана бележка",
            hasPersistedValue = true,
        )

        assertTrue(field.editable)
        assertEquals("Кеширана бележка", field.text)
    }

    @Test
    fun blankServerFieldBecomesEditableAfterLoadingFinishes() {
        val field = ContactNoteScopeFieldLoadPolicy.resolve(
            companyId = "company-1",
            topicState = loadingState.copy(loading = false),
            text = "",
            hasPersistedValue = false,
        )

        assertTrue(field.editable)
        assertEquals("", field.helperText)
    }
}
