package com.onlineimoti.calllog

import android.content.Context

/** Stores the country calling code whose international prefix is shown locally as 0. */
internal object PhoneCountrySettingsStore {
    private const val PREFS = "relationship_manager_prefs"
    private const val KEY_NATIVE_COUNTRY_CALLING_CODE = "native_country_calling_code"

    fun load(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = if (prefs.contains(KEY_NATIVE_COUNTRY_CALLING_CODE)) {
            normalizeCode(prefs.getString(KEY_NATIVE_COUNTRY_CALLING_CODE, "").orEmpty())
        } else {
            PhoneCountryCodeResolver.detectCurrentCallingCode(context).also { detected ->
                prefs.edit().putString(KEY_NATIVE_COUNTRY_CALLING_CODE, detected).apply()
            }
        }
        PhoneNormalizer.configureNativeCountryCode(value)
        return value
    }

    fun save(context: Context, value: String): String {
        val normalized = normalizeCode(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NATIVE_COUNTRY_CALLING_CODE, normalized)
            .apply()
        PhoneNormalizer.configureNativeCountryCode(normalized)
        return normalized
    }

    internal fun normalizeCode(value: String): String {
        var digits = value.filter(Char::isDigit)
        if (digits.startsWith("00")) digits = digits.drop(2)
        return if (digits.length in 1..3) "+$digits" else ""
    }
}
