package com.onlineimoti.calllog

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Builds the profile access and company creation form without owning authentication logic. */
internal class CompanyAccountScreen(private val activity: AppCompatActivity) {
    lateinit var titleText: TextView
    lateinit var descriptionText: TextView
    lateinit var alternativeText: TextView
    lateinit var statusText: TextView
    lateinit var progress: ProgressBar
    lateinit var smsButton: MaterialButton
    lateinit var emailButton: MaterialButton
    lateinit var companyButton: MaterialButton
    lateinit var licenseButton: MaterialButton
    lateinit var phoneInput: EditText
    lateinit var emailInput: EditText
    lateinit var organizationInput: EditText
    lateinit var eikInput: EditText

    fun create(
        onSms: () -> Unit,
        onEmail: () -> Unit,
        onCreateCompany: () -> Unit,
        onLicense: () -> Unit,
    ): View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun params(top: Int = 0) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }
        fun input(hint: String, type: Int) = EditText(activity).apply {
            this.hint = hint
            inputType = type
            setSingleLine(true)
        }

        val root = ScrollView(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(column)

        titleText = TextView(activity).apply { textSize = 24f }
        column.addView(titleText)
        descriptionText = TextView(activity).apply {
            textSize = 16f
            setPadding(0, dp(8), 0, dp(16))
        }
        column.addView(descriptionText)

        phoneInput = input("Телефон", InputType.TYPE_CLASS_PHONE)
        column.addView(phoneInput)
        smsButton = MaterialButton(activity).apply {
            text = "Изпрати SMS код"
            setOnClickListener { onSms() }
        }
        column.addView(smsButton, params(8))

        alternativeText = TextView(activity).apply {
            text = "или"
            textSize = 15f
            gravity = Gravity.CENTER
        }
        column.addView(alternativeText, params(14))

        emailInput = input(
            "Имейл",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )
        column.addView(emailInput, params(10))
        emailButton = MaterialButton(activity).apply {
            text = "Изпрати код по имейл"
            setOnClickListener { onEmail() }
        }
        column.addView(emailButton, params(8))

        organizationInput = input(
            "Име на фирма / организация",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
        )
        eikInput = input("ЕИК / Булстат (незадължително)", InputType.TYPE_CLASS_NUMBER)
        column.addView(organizationInput)
        column.addView(eikInput, params(8))
        companyButton = MaterialButton(activity).apply {
            text = "Създай фирма"
            setOnClickListener { onCreateCompany() }
        }
        column.addView(companyButton, params(16))

        licenseButton = MaterialButton(activity).apply {
            text = "Купи / възстанови лиценз за фирма"
            setOnClickListener { onLicense() }
        }
        column.addView(licenseButton, params(8))

        progress = ProgressBar(activity).apply { visibility = View.GONE }
        column.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        })
        statusText = TextView(activity).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, 0)
        }
        column.addView(statusText)
        return root
    }

    fun showLoading(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
        smsButton.isEnabled = !show
        emailButton.isEnabled = !show
        companyButton.isEnabled = !show
        licenseButton.isEnabled = !show
        phoneInput.isEnabled = !show
        emailInput.isEnabled = !show
        organizationInput.isEnabled = !show
        eikInput.isEnabled = !show
    }
}
