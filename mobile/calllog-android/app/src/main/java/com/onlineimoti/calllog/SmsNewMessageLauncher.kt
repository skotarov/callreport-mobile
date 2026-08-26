package com.onlineimoti.calllog

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

internal object SmsNewMessageLauncher {
    fun show(activity: Activity, dp: (Int) -> Int) {
        val input = EditText(activity).apply {
            hint = "Телефонен номер"
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            AppModalStyle.styleInput(this)
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Нов SMS")
            .setView(wrapper)
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Напред", null)
            .create()
        dialog.setOnShowListener {
            AppModalStyle.styleAlert(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val phone = input.text?.toString().orEmpty().trim()
                if (phone.isBlank()) {
                    input.error = "Въведи телефон"
                    return@setOnClickListener
                }
                dialog.dismiss()
                val title = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty()
                    .ifBlank { PhoneNormalizer.display(phone) }
                    .ifBlank { phone }
                SmsComposeDialog(activity, dp).show(phone = phone, title = title)
            }
        }
        dialog.show()
        input.requestFocus()
    }
}
