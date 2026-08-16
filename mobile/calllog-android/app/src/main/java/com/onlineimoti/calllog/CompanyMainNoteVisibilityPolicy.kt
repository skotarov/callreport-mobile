package com.onlineimoti.calllog

/**
 * General-note history only shows companies that already have a visible note.
 * New notes are created from the single section-level edit action instead of
 * rendering an empty lane for every available company.
 */
internal object CompanyMainNoteVisibilityPolicy {
    fun visibleNotes(
        companyScopeAvailable: Boolean,
        notes: List<CallReportCompanyMainNote>,
    ): List<CallReportCompanyMainNote> = notes.filter { note ->
        note.note.trim().isNotBlank() || note.pending
    }

    fun shouldShow(
        companyScopeAvailable: Boolean,
        notes: List<CallReportCompanyMainNote>,
    ): Boolean = visibleNotes(companyScopeAvailable, notes).isNotEmpty()
}
