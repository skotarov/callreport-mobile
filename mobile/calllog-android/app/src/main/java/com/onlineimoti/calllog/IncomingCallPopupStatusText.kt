package com.onlineimoti.calllog

/** Compact one-glance values for the three permanently labelled popup rows. */
internal object IncomingCallPopupStatusText {
    const val WAITING = "Чака…"
    const val NONE = "Няма"
    const val DISABLED = "Изключен"
    const val ERROR = "Грешка"

    fun compact(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) return WAITING
        val lower = normalized.lowercase()
        return when {
            normalized == IncomingCallPopupProgress.LOADING -> WAITING
            lower.contains("loading") || lower.contains("зареж") -> WAITING
            lower.contains("отговаря бавно") -> WAITING
            lower.startsWith("няма ") || lower == "няма" -> NONE
            lower.contains("не е настроен") || lower.contains("изключен") -> DISABLED
            lower.contains("не отговори") || lower.contains("грешка") -> ERROR
            else -> normalized.replace(Regex("\\s+"), " ")
        }
    }
}
