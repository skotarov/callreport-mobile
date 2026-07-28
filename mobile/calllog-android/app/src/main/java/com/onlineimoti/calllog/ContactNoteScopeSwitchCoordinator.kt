package com.onlineimoti.calllog

/**
 * Switches between Local and company note scopes without discarding an edit.
 *
 * The first spinner binding happens before the editor is ready, so it applies
 * the resolved scope without writing. A real user switch persists the current
 * scope first and changes the visible scope only after that save succeeds.
 */
internal object ContactNoteScopeSwitchCoordinator {
    fun switch(
        currentCompanyId: String,
        nextCompanyId: String,
        editorReady: Boolean,
        persistCurrent: () -> Boolean,
        applyNext: (String) -> Unit,
    ): Boolean {
        val current = normalizedScope(currentCompanyId)
        val next = normalizedScope(nextCompanyId)
        if (editorReady && current != next && !persistCurrent()) return false
        applyNext(next)
        return true
    }

    private fun normalizedScope(companyId: String): String =
        companyId.trim().ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
}
