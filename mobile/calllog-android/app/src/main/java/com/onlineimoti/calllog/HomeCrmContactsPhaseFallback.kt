package com.onlineimoti.calllog

/**
 * Compatibility path for deployments that return an empty Clients result when
 * a phase is sent while the personal-only filter is disabled.
 *
 * The normal filtered server response always wins. We retry only the one broken
 * combination, then apply the existing local phase engine to an unphased server
 * list. Any retry failure preserves the original result.
 */
internal object HomeCrmContactsPhaseFallback {
    fun resolve(
        state: HomeCrmFilterState,
        filteredContacts: List<PhoneCallRecord>,
        loadWithoutPhase: () -> List<PhoneCallRecord>,
        applyPhaseFilter: (List<PhoneCallRecord>) -> List<PhoneCallRecord>,
    ): List<PhoneCallRecord> {
        if (state.crmOnly || !state.hasPhaseFilter || filteredContacts.isNotEmpty()) {
            return filteredContacts
        }
        return runCatching {
            applyPhaseFilter(loadWithoutPhase())
        }.getOrDefault(filteredContacts)
    }
}
