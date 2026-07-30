package com.onlineimoti.calllog

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton

/** Owns the profile/company screen widgets without changing account flow behavior. */
internal class CompanyAccountUi(
    private val activity: CompanyAccountActivity,
    private val onSubmit: () -> Unit,
    private val onSms: () -> Unit,
    private val onSwitchMode: () -> Unit,
    private val onOpenLicense: () -> Unit,
    private val onVerifyEmail: () -> Unit,
    private val onVerifyPhone: () -> Unit,
    private val onLogout: () -> Unit,
) {
    private lateinit var titleText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var submitButton: MaterialButton
    private lateinit var smsButton: MaterialButton
    private lateinit var switchModeButton: MaterialButton
    private lateinit var licenseButton: MaterialButton
    private lateinit var verifyEmailButton: MaterialButton
    private lateinit var verifyPhoneButton: MaterialButton
    private lateinit var logoutButton: MaterialButton
    private lateinit var nameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var organizationInput: EditText
    private lateinit var eikInput: EditText

    fun createContent(): View {
        fun verticalParams(top: Int = 0) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }
        fun editInput(hint: String, inputType: Int): EditText = EditText(activity).apply {
            this.hint = hint
            this.inputType = inputType
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

        nameInput = editInput("Твоето име", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        emailInput = editInput("Имейл", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        phoneInput = editInput("Телефон", InputType.TYPE_CLASS_PHONE)
        organizationInput = editInput("Име на фирма / организация", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        eikInput = editInput("ЕИК / Булстат (незадължително)", InputType.TYPE_CLASS_NUMBER)
        column.addView(nameInput)
        column.addView(emailInput, verticalParams(8))
        column.addView(phoneInput, verticalParams(8))
        column.addView(organizationInput, verticalParams(8))
        column.addView(eikInput, verticalParams(8))

        submitButton = MaterialButton(activity).apply { setOnClickListener { onSubmit() } }
        column.addView(submitButton, verticalParams(16))
        smsButton = MaterialButton(activity).apply {
            text = "Изпрати код със SMS"
            setOnClickListener { onSms() }
        }
        column.addView(smsButton, verticalParams(8))
        verifyEmailButton = MaterialButton(activity).apply {
            text = "Потвърди имейла"
            setOnClickListener { onVerifyEmail() }
        }
        column.addView(verifyEmailButton, verticalParams(8))
        verifyPhoneButton = MaterialButton(activity).apply {
            text = "Потвърди телефона"
            setOnClickListener { onVerifyPhone() }
        }
        column.addView(verifyPhoneButton, verticalParams(8))
        switchModeButton = MaterialButton(activity).apply { setOnClickListener { onSwitchMode() } }
        column.addView(switchModeButton, verticalParams(8))
        logoutButton = MaterialButton(activity).apply {
            text = "Излез"
            setOnClickListener { onLogout() }
        }
        column.addView(logoutButton, verticalParams(8))
        licenseButton = MaterialButton(activity).apply {
            text = "Купи / възстанови лиценз за фирма"
            setOnClickListener { onOpenLicense() }
        }
        column.addView(licenseButton, verticalParams(8))

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

    fun render(
        mode: String,
        hasBaseUrl: Boolean,
        hasActivation: Boolean,
        profile: CompanySessionStore.Snapshot?,
    ) {
        val creatingProfile = mode == CompanyAccountActivity.MODE_REGISTER
        val creatingCompany = mode == CompanyAccountActivity.MODE_CREATE_COMPANY
        val viewingProfile = mode == CompanyAccountActivity.MODE_PROFILE
        val loggingIn = mode == CompanyAccountActivity.MODE_LOGIN

        titleText.text = when {
            viewingProfile -> "Профил"
            creatingProfile -> "Създай профил"
            creatingCompany -> "Създай фирма"
            else -> "Вход в профил"
        }
        descriptionText.text = when {
            viewingProfile -> profileDescription(profile)
            creatingProfile -> "Профилът е с име, имейл и телефон. Имейлът и телефонът се потвърждават с отделни еднократни кодове."
            creatingCompany && !hasActivation -> "Фирмата се създава отделно към влезлия профил. За нея е необходим потвърден лиценз."
            creatingCompany -> "Лицензът е потвърден. Въведи данните на новата фирма."
            else -> "Въведи имейла или телефона на профила и избери къде да получиш еднократния код. Парола не е нужна."
        }

        nameInput.visibility = if (creatingProfile) View.VISIBLE else View.GONE
        emailInput.visibility = if (loggingIn || creatingProfile || viewingProfile) View.VISIBLE else View.GONE
        phoneInput.visibility = if (creatingProfile || viewingProfile) View.VISIBLE else View.GONE
        organizationInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        eikInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        emailInput.hint = if (loggingIn) "Имейл или телефон" else "Имейл"
        if (viewingProfile && profile != null) {
            if (!emailInput.hasFocus()) emailInput.setText(profile.userEmail)
            if (!phoneInput.hasFocus()) phoneInput.setText(profile.userPhone)
        }

        submitButton.visibility = if (viewingProfile) View.GONE else View.VISIBLE
        submitButton.text = if (creatingCompany) "Създай фирма" else "Изпрати код по имейл"
        smsButton.visibility = if (loggingIn || creatingProfile) View.VISIBLE else View.GONE
        verifyEmailButton.visibility = if (viewingProfile) View.VISIBLE else View.GONE
        verifyPhoneButton.visibility = if (viewingProfile) View.VISIBLE else View.GONE
        verifyEmailButton.text = if (profile?.emailVerified == true) "Имейлът е потвърден" else "Потвърди имейла"
        verifyPhoneButton.text = if (profile?.phoneVerified == true) "Телефонът е потвърден" else "Потвърди телефона"
        verifyEmailButton.isEnabled = viewingProfile && profile?.emailVerified != true
        verifyPhoneButton.isEnabled = viewingProfile && profile?.phoneVerified != true
        switchModeButton.text = when {
            viewingProfile -> "Създай фирма"
            creatingCompany -> "Назад към профила"
            creatingProfile -> "Вече имам профил"
            else -> "Създай профил"
        }
        logoutButton.visibility = if (viewingProfile || creatingCompany) View.VISIBLE else View.GONE
        licenseButton.visibility = if (creatingCompany && !hasActivation) View.VISIBLE else View.GONE
        submitButton.isEnabled = hasBaseUrl && (!creatingCompany || profile != null && hasActivation)
        smsButton.isEnabled = hasBaseUrl

        when {
            !hasBaseUrl -> setStatus("Първо настрой сървърния адрес от Настройки → Профил и фирми.")
            creatingCompany && !hasActivation -> setStatus("Първо купи или възстанови лиценз за новата фирма.")
            viewingProfile && profile?.profileReady != true -> setStatus("Потвърди и имейла, и телефона, за да е завършен профилът.")
            viewingProfile -> setStatus("При следващ вход сървърът ще издаде нов ключ и ще анулира предишния.")
            else -> setStatus("")
        }
    }

    fun registrationName(): String = nameInput.text?.toString().orEmpty().trim()
    fun emailOrIdentifier(): String = emailInput.text?.toString().orEmpty().trim()
    fun phone(): String = phoneInput.text?.toString().orEmpty().trim()
    fun organizationName(): String = organizationInput.text?.toString().orEmpty().trim()
    fun eik(): String = eikInput.text?.toString().orEmpty()

    fun clearProfileInputs() {
        nameInput.text?.clear()
        emailInput.text?.clear()
        phoneInput.text?.clear()
    }

    fun clearLoginContacts() {
        emailInput.text?.clear()
        phoneInput.text?.clear()
    }

    fun showOtpDialog(
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
            .setMessage("Кодът е изпратен до ${challenge.destinationHint} и е валиден 10 минути.")
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

    fun showLoading(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
        submitButton.isEnabled = !show
        smsButton.isEnabled = !show
        switchModeButton.isEnabled = !show
        licenseButton.isEnabled = !show
        verifyEmailButton.isEnabled = !show
        verifyPhoneButton.isEnabled = !show
        logoutButton.isEnabled = !show
    }

    fun setStatus(value: String) {
        statusText.text = value
    }

    private fun profileDescription(profile: CompanySessionStore.Snapshot?): String {
        if (profile == null) return "Няма активен профил."
        val name = profile.userName.ifBlank { "Без име" }
        val email = profile.userEmail.ifBlank { "Няма въведен имейл" }
        val phone = profile.userPhone.ifBlank { "Няма въведен телефон" }
        val emailState = if (profile.emailVerified) "потвърден" else "непотвърден"
        val phoneState = if (profile.phoneVerified) "потвърден" else "непотвърден"
        return "Влязъл профил: $name\nИмейл: $email · $emailState\nТелефон: $phone · $phoneState"
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
