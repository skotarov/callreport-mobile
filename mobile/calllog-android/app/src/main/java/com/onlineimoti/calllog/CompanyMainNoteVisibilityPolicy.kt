package com.onlineimoti.calllog

/**
 * CRM/company scope controls whether empty company lanes may be shown and new
 * notes may be added. Existing server notes remain visible independently.
 */
internal object CompanyMainNoteVisibilityPolicy {
    fun visibleNotes(
        companyScopeAvailable: Boolean,
        notes: List<CallReportCompanyMainNote>,
    ): List<CallReportCompanyMainNote> = if (companyScopeAvailable) {
        notes
    } else {
        notes.filter { note -> note.note.trim().isNotBlank() || note.pending }
    }

    fun shouldShow(
        companyScopeAvailable: Boolean,
        notes: List<CallReportCompanyMainNote>,
    ): Boolean = visibleNotes(companyScopeAvailable, notes).isNotEmpty()
}
