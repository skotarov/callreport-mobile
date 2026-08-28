package com.onlineimoti.calllog

/** Visual hierarchy for contact names written as "Name | detail | extra detail". */
internal data class ContactNamePresentation(
    val fullName: String,
    val primary: String,
    val secondary: List<String>,
) {
    companion object {
        fun from(value: String): ContactNamePresentation {
            val parts = value.split('|').map { it.trim() }.filter(String::isNotBlank)
            return ContactNamePresentation(
                fullName = parts.joinToString(" | "),
                primary = parts.firstOrNull().orEmpty(),
                secondary = parts.drop(1),
            )
        }
    }
}
