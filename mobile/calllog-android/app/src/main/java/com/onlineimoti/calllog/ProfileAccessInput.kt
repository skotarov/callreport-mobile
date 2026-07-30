package com.onlineimoti.calllog

/** One verified email or phone number is enough to create or enter a profile. */
internal data class ProfileAccessTarget(
    val identifier: String,
    val channel: String,
)

internal object ProfileAccessInput {
    fun parse(rawValue: String): ProfileAccessTarget? {
        val value = rawValue.trim()
        if (value.isBlank()) return null

        if ('@' in value) {
            val parts = value.split('@')
            val valid = parts.size == 2 &&
                parts[0].isNotBlank() &&
                parts[1].contains('.') &&
                !value.any(Char::isWhitespace)
            return if (valid) ProfileAccessTarget(value, "email") else null
        }

        if (value.any { it.isLetter() }) return null
        val digits = value.filter(Char::isDigit)
        if (digits.length !in 8..15) return null
        return ProfileAccessTarget(value, "sms")
    }
}
