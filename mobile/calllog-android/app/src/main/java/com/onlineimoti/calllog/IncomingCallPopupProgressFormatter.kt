package com.onlineimoti.calllog

internal object IncomingCallPopupProgressFormatter {
    private const val MAX_LOCAL_NOTES_IN_ROW = 2
    private const val MAX_SERVER_NOTES_IN_ROW = 3
    private const val ICON_GENERAL_NOTE = "☰"
    private const val ICON_CALL_NOTE = "💬"

    fun build(
        remoteAvailable: Boolean,
        localRows: List<String>?,
        remoteRows: List<PostCallLookupRemoteRow>?,
        historyFinished: Boolean,
        historyFailed: Boolean,
        serverSlow: Boolean,
        lookupFinished: Boolean,
        lookupSucceeded: Boolean,
    ): IncomingCallPopupProgress {
        val callLine = when (localRows) {
            null -> IncomingCallPopupProgress.LOADING
            else -> localRows.firstOrNull { !isLocalNoteRow(it) }.orEmpty()
                .ifBlank { "Няма предишни разговори" }
        }
        val localNoteLine = when (localRows) {
            null -> IncomingCallPopupProgress.LOADING
            else -> localRows.asSequence()
                .filter(::isLocalNoteRow)
                .map(::stripLocalNoteIcon)
                .filter { it.isNotBlank() }
                .take(MAX_LOCAL_NOTES_IN_ROW)
                .joinToString(" • ")
                .ifBlank { "Няма локални бележки" }
        }
        val serverNoteLine = when {
            !remoteAvailable -> "Сървърът не е настроен"
            remoteRows?.isNotEmpty() == true -> remoteRows.asSequence()
                .map(::formatRemoteRow)
                .filter { it.isNotBlank() }
                .take(MAX_SERVER_NOTES_IN_ROW)
                .joinToString(" • ")
            historyFinished && historyFailed -> "Сървърът не отговори"
            historyFinished -> "Няма сървърни бележки"
            serverSlow -> "Сървърът отговаря бавно…"
            lookupFinished && !lookupSucceeded -> "Сървърът не отговори"
            else -> IncomingCallPopupProgress.LOADING
        }
        return IncomingCallPopupProgress(callLine, localNoteLine, serverNoteLine)
    }

    private fun isLocalNoteRow(value: String): Boolean =
        value.startsWith(ICON_GENERAL_NOTE) || value.startsWith(ICON_CALL_NOTE)

    private fun stripLocalNoteIcon(value: String): String = value
        .removePrefix(ICON_GENERAL_NOTE)
        .removePrefix(ICON_CALL_NOTE)
        .trim()
        .replace(Regex("\s+"), " ")

    private fun formatRemoteRow(row: PostCallLookupRemoteRow): String =
        listOf(row.companyName.ifBlank { "Сървър" }, row.note.trim())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}
