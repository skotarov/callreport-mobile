package com.onlineimoti.calllog

import java.util.Locale

/**
 * One identity model for communication peers. Numeric values keep the existing
 * phone normalization, while alphanumeric SMS senders keep their provider address.
 */
internal data class CommunicationAddress private constructor(
    val raw: String,
    val phoneKey: String,
) {
    val isValid: Boolean
        get() = raw.isNotBlank()

    val isPhone: Boolean
        get() = phoneKey.isNotBlank()

    fun matches(candidate: String): Boolean {
        val safeCandidate = candidate.trim()
        if (!isValid || safeCandidate.isBlank()) return false
        return if (isPhone) {
            PhoneNormalizer.samePhone(raw, safeCandidate)
        } else {
            textKey(raw) == textKey(safeCandidate)
        }
    }

    companion object {
        fun from(value: String): CommunicationAddress = resolved(value, PhoneNormalizer.key(value))

        internal fun resolved(value: String, phoneKey: String): CommunicationAddress =
            CommunicationAddress(value.trim(), phoneKey.trim())

        private fun textKey(value: String): String = value.trim().lowercase(Locale.ROOT)
    }
}
