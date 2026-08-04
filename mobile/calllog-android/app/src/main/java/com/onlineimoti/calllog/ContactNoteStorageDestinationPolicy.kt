package com.onlineimoti.calllog

internal enum class ContactNoteStorageDestination {
    LOCAL,
    SERVER,
}

/** Pure destination classification shared by the note storage message and its tests. */
internal object ContactNoteStorageDestinationPolicy {
    fun resolve(
        selectedCompanyId: String,
        fallbackLocalOnly: Boolean,
    ): ContactNoteStorageDestination = when {
        selectedCompanyId == ContactNoteTopicState.LOCAL_COMPANY_ID -> ContactNoteStorageDestination.LOCAL
        selectedCompanyId.isBlank() && fallbackLocalOnly -> ContactNoteStorageDestination.LOCAL
        else -> ContactNoteStorageDestination.SERVER
    }
}
