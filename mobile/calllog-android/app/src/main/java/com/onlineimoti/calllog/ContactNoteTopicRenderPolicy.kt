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
        // Loading is visible even when the company list came from cache. The server
        // note text can still be in flight and the fields must be rebound once that
        // request finishes; otherwise the first editor open can remain blank.
        state.loading -> "loading"
        state.loadError.isNotBlank() -> "error"
        state.usingCachedCompanies -> "cached"
        else -> ""
    }
}
