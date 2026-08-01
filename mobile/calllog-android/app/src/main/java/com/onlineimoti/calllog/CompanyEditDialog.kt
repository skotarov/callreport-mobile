package com.onlineimoti.calllog

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Owner-only editor for mutable company profile fields. */
internal object CompanyEditDialog {
    fun show(
        activity: AppCompatActivity,
        company: CallReportTopicCompany,
        onSaved: () -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        fun params(top: Int = 0) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }

        val nameInput = EditText(activity).apply {
            hint = "Име на фирмата"
            setText(company.name)
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
        }
        val eikInput = EditText(activity).apply {
            hint = "ЕИК / Булстат"
            setText(company.eik)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val validation = TextView(activity).apply {
            textSize = 13f
            visibility = View.GONE
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(TextView(activity).apply {
                text = "ID: ${company.id}"
                textSize = 13f
                setTextIsSelectable(true)
            })
            addView(nameInput, params(10))
            addView(eikInput, params(8))
            addView(validation, params(6))
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Редактирай фирма")
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Запази", null)
            .create()

        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val name = nameInput.text?.toString().orEmpty().trim()
                val eik = eikInput.text?.toString().orEmpty().trim()
                when {
                    name.isBlank() -> {
                        validation.text = "Името на фирмата е задължително."
                        validation.visibility = View.VISIBLE
                    }
                    name.length > 120 -> {
                        validation.text = "Името може да бъде най-много 120 знака."
                        validation.visibility = View.VISIBLE
                    }
                    eik.length > 20 -> {
                        validation.text = "ЕИК/Булстат може да бъде най-много 20 знака."
                        validation.visibility = View.VISIBLE
                    }
                    else -> {
                        validation.visibility = View.GONE
                        save.isEnabled = false
                        nameInput.isEnabled = false
                        eikInput.isEnabled = false
                        Thread {
                            val result = AccountMutationOutbox.enqueueCompanyUpdate(
                                context = activity.applicationContext,
                                companyId = company.id,
                                name = name,
                                eik = eik,
                            )
                            activity.runOnUiThread {
                                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                result.onSuccess {
                                    dialog.dismiss()
                                    Toast.makeText(
                                        activity,
                                        "Данните са записани. При нужда ще се синхронизират автоматично.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    onSaved()
                                }.onFailure { error ->
                                    save.isEnabled = true
                                    nameInput.isEnabled = true
                                    eikInput.isEnabled = true
                                    validation.text = error.message
                                        ?.takeIf(String::isNotBlank)
                                        ?: "Данните не можаха да бъдат записани."
                                    validation.visibility = View.VISIBLE
                                }
                            }
                        }.start()
                    }
                }
            }
        }
        dialog.show()
    }
}
