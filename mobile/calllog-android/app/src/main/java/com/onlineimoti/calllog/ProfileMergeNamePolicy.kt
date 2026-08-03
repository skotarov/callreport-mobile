package com.onlineimoti.calllog

internal object ProfileMergeNamePolicy {
    fun options(currentProfileName: String, existingProfileName: String): List<String> =
        listOf(currentProfileName, existingProfileName)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

    fun isAllowed(selectedName: String, options: List<String>): Boolean {
        val selected = selectedName.trim()
        return selected.isNotBlank() && options.any { it == selected }
    }
}
