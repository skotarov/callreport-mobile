package com.onlineimoti.calllog

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Explicit confirmation before two verified-contact profiles are merged. */
internal object ProfileMergeDialog {
    fun show(
        activity: AppCompatActivity,
        verification: CompanyAccountApi.ContactVerification,
        channel: String,
        onConfirm: () -> Unit,
    ) {
        val contactLabel = if (channel == "email") "имейлът" else "телефонът"
        val existing = buildList {
            verification.existingProfileName.takeIf(String::isNotBlank)?.let { add(it) }
            verification.existingProfileEmail.takeIf(String::isNotBlank)?.let { add(it) }
            verification.existingProfilePhone.takeIf(String::isNotBlank)?.let { add(PhoneNormalizer.display(it)) }
        }.distinct().joinToString(" · ")

        val details = if (existing.isBlank()) "" else "\n\nДругият профил: $existing"
        AlertDialog.Builder(activity)
            .setTitle("Обедини профилите?")
            .setMessage(
                "Този $contactLabel е свързан с друг твой профил. " +
                    "При обединяване текущият профил и вход остават активни. " +
                    "Фирмите и ролите от двата профила ще бъдат събрани, " +
                    "а другият профил ще бъде деактивиран.$details",
            )
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Обедини") { _, _ -> onConfirm() }
            .show()
    }
}
