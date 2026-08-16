package com.onlineimoti.calllog

/** Keeps cache-first company refreshes from rebuilding unchanged note fields. */
internal object ContactNoteTopicRenderPolicy {
    fun shouldRebind(
        before: ContactNoteTopicState,
        after: ContactNoteTopicState,
        scopeValuesChanged: Boolean,
    ): Boolean {
        if (scopeValuesChanged) return true
        if (companySignature(before) != companySignature(after)) return true
        return visibleStatus(before) != visibleStatus(after)
    }

    private fun companySignature(state: ContactNoteTopicState): List<List<Any>> =
        state.companies
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { company ->
                listOf(
                    company.id,
                    company.name,
                    company.role,
                    company.canManageUsers,
                    company.eik,
                    company.createdAtMs,
                    company.updatedAtMs,
                )
            }

    private fun visibleStatus(state: ContactNoteTopicState): String = when {
        state.loading && state.companies.isEmpty() -> "loading"
        state.loadError.isNotBlank() -> "error"
        state.usingCachedCompanies && !state.loading -> "cached"
        else -> ""
    }
}
