package com.onlineimoti.calllog

/** Keeps the History header current after returning from the Android Contacts app. */
internal object ContactNotesTitleRefreshPolicy {
    fun updatedTitle(currentTitle: String, contactsDisplayName: String?): String {
        return contactsDisplayName?.trim()?.takeIf { it.isNotBlank() } ?: currentTitle
    }
}
