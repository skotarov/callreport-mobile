package com.onlineimoti.calllog

/** Visual hierarchy for contact names written as "Name | detail | extra detail". */
internal data class ContactNamePresentation(
    val fullName: String,
    val primary: String,
    val secondary: List<String>,
) {
    companion object {
        fun from(value: String): ContactNamePresentation {
            val parts = buildList {
                value.split('|').forEach { segment ->
                    val parenthesized = PARENTHESIZED_CONTENT.findAll(segment).toList()
                    val outside = PARENTHESIZED_CONTENT.replace(segment, " ")
                        .trim()
                        .replace(Regex("\\s+"), " ")
                    if (outside.isNotBlank()) add(outside)
                    parenthesized.map { it.groupValues[1].trim() }
                        .filter(String::isNotBlank)
                        .forEach(::add)
                }
            }
            return ContactNamePresentation(
                fullName = value.trim(),
                primary = parts.firstOrNull().orEmpty(),
                secondary = parts.drop(1),
            )
        }

        private val PARENTHESIZED_CONTENT = Regex("\\(([^()]*)\\)")
    }
}
