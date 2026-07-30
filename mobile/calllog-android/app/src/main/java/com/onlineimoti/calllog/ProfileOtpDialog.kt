package com.onlineimoti.calllog

import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Displays and validates the six-digit OTP entry dialog. */
internal object ProfileOtpDialog {
    fun show(
        activity: AppCompatActivity,
        challenge: CompanyAccountApi.OtpChallenge,
        title: String,
        verify: (String) -> Unit,
    ) {
        val codeInput = EditText(activity).apply {
            hint = "Шестцифрен код"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            if (challenge.debugCode.isNotBlank()) setText(challenge.debugCode)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage("Кодът е изпратен до ${challenge.destinationHint}.")
            .setView(codeInput)
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Потвърди", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = codeInput.text?.toString().orEmpty().trim()
                if (code.length != 6) {
                    codeInput.error = "Въведи шестцифрения код"
                    return@setOnClickListener
                }
                dialog.dismiss()
                verify(code)
            }
        }
        dialog.show()
    }
}
