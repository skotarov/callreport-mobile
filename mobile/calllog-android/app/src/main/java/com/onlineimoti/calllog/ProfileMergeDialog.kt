package com.onlineimoti.calllog

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Explicit confirmation and surviving-name selection before verified profiles are merged. */
internal object ProfileMergeDialog {
    fun show(
        activity: AppCompatActivity,
        verification: CompanyAccountApi.ContactVerification,
        channel: String,
        onConfirm: (String) -> Unit,
    ) {
        val nameOptions = ProfileMergeNamePolicy.options(
            currentProfileName = verification.user.name,
            existingProfileName = verification.existingProfileName,
        )
        if (nameOptions.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("Обединяването е спряно")
                .setMessage("Сървърът не върна валидни имена за двата профила.")
                .setPositiveButton("Затвори", null)
                .show()
            return
        }

        val contactLabel = if (channel == "email") "имейлът" else "телефонът"
        val existingContact = buildList {
            verification.existingProfileEmail.takeIf(String::isNotBlank)?.let { add(it) }
            verification.existingProfilePhone.takeIf(String::isNotBlank)?.let { add(PhoneNormalizer.display(it)) }
        }.distinct().joinToString(" · ")
        val details = if (existingContact.isBlank()) "" else "\nДругият профил: $existingContact\n"
        var selectedIndex = 0

        AlertDialog.Builder(activity)
            .setTitle("Кое име да остане?")
            .setMessage(
                "Този $contactLabel е свързан с друг твой профил.$details\n" +
                    "Фирмите, по-високите роли и всички собствености ще преминат към текущото ID. " +
                    "Другият профил ще бъде изтрит. Избери едно от двете съществуващи имена:",
            )
            .setSingleChoiceItems(nameOptions.toTypedArray(), selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Обедини") { _, _ ->
                val selectedName = nameOptions.getOrNull(selectedIndex).orEmpty()
                if (ProfileMergeNamePolicy.isAllowed(selectedName, nameOptions)) {
                    onConfirm(selectedName)
                }
            }
            .show()
    }
}
