package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCrmFilterStateTest {
    @Test
    fun emptySelectionsDoNotActivatePhaseOrCompanyFilters() {
        val state = HomeCrmFilterState()

        assertFalse(state.hasPhaseFilter)
        assertFalse(state.hasCompanyFilter)
        assertFalse(state.isCompanyFiltered)
        assertFalse(state.isActive)
    }

    @Test
    fun selectedPhaseActivatesOnlyPhaseFilter() {
        val state = HomeCrmFilterState(phases = setOf(ContactNegotiationPhaseStore.PHASE_2))

        assertTrue(state.hasPhaseFilter)
        assertFalse(state.hasCompanyFilter)
        assertFalse(state.isCompanyFiltered)
        assertTrue(state.isActive)
    }

    @Test
    fun selectedCompanyActivatesCompanyFilterWithoutRequiringPhase() {
        val state = HomeCrmFilterState(companyIds = setOf("company-1"))

        assertFalse(state.hasPhaseFilter)
        assertTrue(state.hasCompanyFilter)
        assertTrue(state.isCompanyFiltered)
        assertTrue(state.isActive)
    }
}
