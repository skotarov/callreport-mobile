package com.onlineimoti.calllog

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/** Detects a sensible first-run country from the current network, SIM, then device locale. */
internal object PhoneCountryCodeResolver {
    fun detectCurrentCallingCode(context: Context): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val regions = linkedSetOf<String>()
        try {
            telephony?.networkCountryIso?.let(regions::add)
        } catch (_: SecurityException) {
        }
        try {
            telephony?.simCountryIso?.let(regions::add)
        } catch (_: SecurityException) {
        }
        regions.add(Locale.getDefault().country)

        val phoneNumberUtil = PhoneNumberUtil.getInstance()
        regions.asSequence()
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.length == 2 }
            .forEach { region ->
                val code = runCatching { phoneNumberUtil.getCountryCodeForRegion(region) }.getOrDefault(0)
                if (code > 0) return "+$code"
            }
        return ""
    }
}
