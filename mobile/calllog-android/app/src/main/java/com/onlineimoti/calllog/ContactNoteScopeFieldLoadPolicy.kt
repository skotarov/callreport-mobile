package com.onlineimoti.calllog

internal data class ContactNoteScopeFieldUiState(
    val text: String,
    val editable: Boolean = true,
    val helperText: String = "",
)

/** Prevents a blank company field from looking authoritative before server data arrives. */
internal object ContactNoteScopeFieldLoadPolicy {
    fun resolve(
        companyId: String,
        topicState: ContactNoteTopicState,
        text: String,
        hasPersistedValue: Boolean,
    ): ContactNoteScopeFieldUiState {
        val waitingForServerValue = companyId != ContactNoteTopicState.LOCAL_COMPANY_ID &&
            topicState.loading &&
            topicState.loadError.isBlank() &&
            text.isBlank() &&
            !hasPersistedValue
        if (!waitingForServerValue) return ContactNoteScopeFieldUiState(text = text)
        return ContactNoteScopeFieldUiState(
            text = "",
            editable = false,
            helperText = if (AppLocaleText.isBulgarian()) {
                "Зареждам бележката от сървъра…"
            } else {
                "Loading note from server…"
            },
        )
    }
}
