package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientsCompanyAvailabilityRefreshTest {
    @Test fun noCompanySelectionReloadsWhenAccessibleCompaniesArrive() {
        assertTrue(
            shouldReloadClientsAfterCompanyRefresh(
                accessibleCompanyIdsChanged = true,
                selectionChanged = false,
                hasCompanyFilter = false,
            ),
        )
    }

    @Test fun selectedCompanyDoesNotReloadForUnrelatedAccessibleCompanyChange() {
        assertFalse(
            shouldReloadClientsAfterCompanyRefresh(
                accessibleCompanyIdsChanged = true,
                selectionChanged = false,
                hasCompanyFilter = true,
            ),
        )
    }

    @Test fun removedUnavailableSelectionAlwaysReloads() {
        assertTrue(
            shouldReloadClientsAfterCompanyRefresh(
                accessibleCompanyIdsChanged = true,
                selectionChanged = true,
                hasCompanyFilter = false,
            ),
        )
    }

    @Test fun unchangedAccessibleScopeWithoutSelectionDoesNotLoopReload() {
        assertFalse(
            shouldReloadClientsAfterCompanyRefresh(
                accessibleCompanyIdsChanged = false,
                selectionChanged = false,
                hasCompanyFilter = false,
            ),
        )
    }
}
