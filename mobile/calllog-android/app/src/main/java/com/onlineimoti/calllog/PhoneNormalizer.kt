package com.onlineimoti.calllog

import java.net.URLDecoder

object PhoneNormalizer {
    private const val NATIONAL_PREFIX = "0"
    private const val LEGACY_PHONE_KEY_LENGTH = 9

    @Volatile
    private var nativeCountryCodeDigits: String = ""

    /** Configures which international country code is represented locally by a leading 0. */
    fun configureNativeCountryCode(value: String) {
        var digits = value.filter(Char::isDigit)
        if (digits.startsWith("00")) digits = digits.drop(2)
        nativeCountryCodeDigits = digits.takeIf { it.length in 1..3 }.orEmpty()
    }

    /**
     * Canonical value used for server requests. Explicit international numbers keep +,
     * while a local 0-prefix is expanded only when a native country code is configured.
     */
    fun normalize(value: String): String {
        val parsed = parse(value)
        if (parsed.digits.isBlank()) return ""

        val countryCode = nativeCountryCodeDigits
        return when {
            parsed.explicitInternational -> "+${parsed.digits}"
            countryCode.isNotBlank() && parsed.digits.startsWith(countryCode) -> "+${parsed.digits}"
            countryCode.isNotBlank() &&
                parsed.digits.startsWith(NATIONAL_PREFIX) &&
                parsed.digits.length >= 7 -> "+$countryCode${parsed.digits.drop(1)}"
            else -> parsed.digits
        }
    }

    /**
     * Legacy local key retained so existing notes and Android contact links remain readable.
     * Server/profile identity must use [normalize], not this shortened key.
     */
    fun key(value: String): String {
        val digits = parse(value).digits
        return if (digits.length > LEGACY_PHONE_KEY_LENGTH) digits.takeLast(LEGACY_PHONE_KEY_LENGTH) else digits
    }

    fun samePhone(left: String, right: String): Boolean {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        if (normalizedLeft.startsWith("+") && normalizedRight.startsWith("+")) {
            return normalizedLeft == normalizedRight
        }
        val leftKey = key(left)
        val rightKey = key(right)
        return leftKey.isNotBlank() && leftKey == rightKey
    }

    /** Candidate forms used when Android providers store local or international variants. */
    fun candidates(value: String): List<String> {
        val decoded = cleanInput(value)
        val parsed = parse(value)
        val normalized = normalize(value)
        val countryCode = nativeCountryCodeDigits

        return linkedSetOf<String>().apply {
            add(decoded)
            add(parsed.digits)
            add(normalized)

            if (countryCode.isNotBlank() && normalized.startsWith("+$countryCode")) {
                val nationalNumber = normalized.removePrefix("+$countryCode")
                if (nationalNumber.isNotBlank()) {
                    add(nationalNumber)
                    add("$NATIONAL_PREFIX$nationalNumber")
                    add("$countryCode$nationalNumber")
                    add("+$countryCode$nationalNumber")
                    add("00$countryCode$nationalNumber")
                }
            }
        }.filter { it.isNotBlank() }
    }

    /**
     * Replaces only the selected native country code with 0 for display.
     * Other international numbers remain visibly foreign with their +country code.
     */
    fun display(value: String): String {
        val countryCode = nativeCountryCodeDigits
        if (countryCode.isBlank()) return cleanInput(value).ifBlank { value.trim() }

        val normalized = normalize(value)
        if (!normalized.startsWith("+$countryCode")) {
            return normalized.ifBlank { cleanInput(value) }
        }

        val nationalNumber = normalized.removePrefix("+$countryCode")
        if (nationalNumber.isBlank()) return normalized
        val local = "$NATIONAL_PREFIX$nationalNumber"
        return if (countryCode == "359" && local.length == 10) {
            "${local.take(4)} ${local.drop(4).take(3)} ${local.drop(7)}"
        } else {
            local
        }
    }

    private data class ParsedPhone(
        val digits: String,
        val explicitInternational: Boolean,
    )

    private fun parse(value: String): ParsedPhone {
        val decoded = cleanInput(value)
        var digits = decoded.filter(Char::isDigit)
        var explicitInternational = decoded.trimStart().startsWith("+")
        if (digits.startsWith("00") && digits.length > 4) {
            digits = digits.drop(2)
            explicitInternational = true
        }
        return ParsedPhone(digits, explicitInternational)
    }

    private fun cleanInput(value: String): String {
        val protectedPlus = value.trim().replace("+", "%2B")
        val decoded = runCatching { URLDecoder.decode(protectedPlus, "UTF-8") }
            .getOrDefault(value.trim())
        return decoded
            .removePrefix("tel:")
            .removePrefix("phone:")
            .removePrefix("web_search:")
            .substringBefore('?')
            .substringBefore(';')
            .trim()
    }
}
