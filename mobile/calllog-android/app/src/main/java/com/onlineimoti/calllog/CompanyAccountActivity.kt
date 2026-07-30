package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** OTP profile registration/login and company creation. Profile editing has its own screen. */
class CompanyAccountActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var titleText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var submitButton: MaterialButton
    private lateinit var smsButton: MaterialButton
    private lateinit var switchModeButton: MaterialButton
    private lateinit var licenseButton: MaterialButton
    private lateinit var nameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var organizationInput: EditText
    private lateinit var eikInput: EditText

    private var mode: String = MODE_LOGIN

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        val requestedMode = intent.getStringExtra(EXTRA_MODE)
        if (requestedMode == MODE_PROFILE && ConfigStore.load(this).accessToken.isNotBlank()) {
            startActivity(Intent(this, ProfileEditorActivity::class.java))
            finish()
            return
        }
        mode = when {
            requestedMode == MODE_CREATE_COMPANY && CompanySessionStore.load(this) != null -> MODE_CREATE_COMPANY
            requestedMode == MODE_REGISTER -> MODE_REGISTER
            else -> MODE_LOGIN
        }
        title = if (mode == MODE_CREATE_COMPANY) "Създай фирма" else "Профил"
        setContentView(createContent())
        renderMode()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createContent(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun params(top: Int = 0) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }
        fun input(hint: String, type: Int) = EditText(this).apply {
            this.hint = hint
            inputType = type
            setSingleLine(true)
        }

        val root = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(column)

        titleText = TextView(this).apply { textSize = 24f }
        column.addView(titleText)
        descriptionText = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(8), 0, dp(16))
        }
        column.addView(descriptionText)

        nameInput = input("Твоето име", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        emailInput = input("Имейл", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        phoneInput = input("Телефон", InputType.TYPE_CLASS_PHONE)
        organizationInput = input("Име на фирма / организация", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        eikInput = input("ЕИК / Булстат (незадължително)", InputType.TYPE_CLASS_NUMBER)
        column.addView(nameInput)
        column.addView(emailInput, params(8))
        column.addView(phoneInput, params(8))
        column.addView(organizationInput, params(8))
        column.addView(eikInput, params(8))

        submitButton = MaterialButton(this).apply { setOnClickListener { submitPrimary() } }
        column.addView(submitButton, params(16))
        smsButton = MaterialButton(this).apply {
            text = "Изпрати код със SMS"
            setOnClickListener { submitSms() }
        }
        column.addView(smsButton, params(8))
        switchModeButton = MaterialButton(this).apply { setOnClickListener { switchMode() } }
        column.addView(switchModeButton, params(8))
        licenseButton = MaterialButton(this).apply {
            text = "Купи / възстанови лиценз за фирма"
            setOnClickListener { startActivity(Intent(this@CompanyAccountActivity, CompanyLicenseActivity::class.java)) }
        }
        column.addView(licenseButton, params(8))

        progress = ProgressBar(this).apply { visibility = View.GONE }
        column.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        })
        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, 0)
        }
        column.addView(statusText)
        return root
    }

    private fun renderMode() {
        val hasBaseUrl = ConfigStore.load(this).baseUrl.isNotBlank()
        val activation = CompanyLicenseStore.loadValid(this)
        val creatingProfile = mode == MODE_REGISTER
        val creatingCompany = mode == MODE_CREATE_COMPANY

        titleText.text = when {
            creatingCompany -> "Създай фирма"
            creatingProfile -> "Създай профил"
            else -> "Вход в профил"
        }
        descriptionText.text = when {
            creatingCompany && activation == null -> "За нова фирма е необходим потвърден лиценз. След създаването текущият профил става собственик."
            creatingCompany -> "Въведи данните на новата фирма. Текущият профил ще бъде добавен като собственик."
            creatingProfile -> "Въведи име, имейл и телефон. Имейлът и телефонът се потвърждават с отделни еднократни кодове."
            else -> "Въведи имейла или телефона и избери къде да получиш еднократния код. Парола не е нужна."
        }

        nameInput.visibility = if (creatingProfile) View.VISIBLE else View.GONE
        emailInput.visibility = if (!creatingCompany) View.VISIBLE else View.GONE
        phoneInput.visibility = if (creatingProfile) View.VISIBLE else View.GONE
        organizationInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        eikInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        emailInput.hint = if (creatingProfile) "Имейл" else "Имейл или телефон"

        submitButton.text = when {
            creatingCompany -> "Създай фирма"
            else -> "Изпрати код по имейл"
        }
        smsButton.visibility = if (creatingCompany) View.GONE else View.VISIBLE
        switchModeButton.text = when {
            creatingCompany -> "Отказ"
            creatingProfile -> "Вече имам профил"
            else -> "Създай профил"
        }
        licenseButton.visibility = if (creatingCompany && activation == null) View.VISIBLE else View.GONE
        submitButton.isEnabled = hasBaseUrl && (!creatingCompany || activation != null)
        smsButton.isEnabled = hasBaseUrl

        when {
            !hasBaseUrl -> setStatus("Първо настрой сървърния адрес от Настройки → Профил.")
            creatingCompany && CompanySessionStore.load(this) == null -> setStatus("Първо влез в профила.")
            creatingCompany && activation == null -> setStatus("Първо купи или възстанови лиценз за новата фирма.")
            else -> setStatus("")
        }
    }

    private fun switchMode() {
        if (mode == MODE_CREATE_COMPANY) {
            finish()
            return
        }
        mode = if (mode == MODE_REGISTER) MODE_LOGIN else MODE_REGISTER
        nameInput.text?.clear()
        emailInput.text?.clear()
        phoneInput.text?.clear()
        renderMode()
    }

    private fun submitPrimary() {
        when (mode) {
            MODE_REGISTER -> requestRegistrationCode("email")
            MODE_CREATE_COMPANY -> createCompany()
            else -> requestLoginCode("email")
        }
    }

    private fun submitSms() {
        when (mode) {
            MODE_REGISTER -> requestRegistrationCode("sms")
            MODE_LOGIN -> requestLoginCode("sms")
        }
    }

    private fun requestRegistrationCode(channel: String) {
        val name = nameInput.text?.toString().orEmpty().trim()
        val email = emailInput.text?.toString().orEmpty().trim()
        val phone = phoneInput.text?.toString().orEmpty().trim()
        if (name.isBlank() || email.isBlank() || phone.isBlank()) {
            setStatus("Въведи име, имейл и телефон.")
            return
        }
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.requestRegistrationOtp(applicationContext, email, phone, name, channel)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { challenge ->
                    showOtpDialog(challenge, "Потвърждение на профила") { code ->
                        verifyRegistrationCode(challenge, code)
                    }
                }.onFailure { error -> setStatus(error.message ?: "Кодът не можа да бъде изпратен.") }
            }
        }
    }

    private fun verifyRegistrationCode(challenge: CompanyAccountApi.OtpChallenge, code: String) {
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.verifyRegistrationOtp(applicationContext, challenge.id, code)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { verified ->
                    verified.session?.let { session ->
                        CompanyAccountApi.applySession(applicationContext, session)
                        openProfileAndFinish()
                        return@onSuccess
                    }
                    val next = if (verified.user.emailVerified) {
                        "Имейлът е потвърден. Сега изпрати код със SMS."
                    } else {
                        "Телефонът е потвърден. Сега изпрати код по имейл."
                    }
                    setStatus(next)
                }.onFailure { error -> setStatus(error.message ?: "Кодът не е приет.") }
            }
        }
    }

    private fun requestLoginCode(channel: String) {
        val identifier = emailInput.text?.toString().orEmpty().trim()
        if (identifier.isBlank()) {
            setStatus("Въведи имейл или телефон.")
            return
        }
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.requestLoginOtp(applicationContext, identifier, channel)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { challenge ->
                    showOtpDialog(challenge, "Вход в профил") { code -> verifyLoginCode(challenge, code) }
                }.onFailure { error -> setStatus(error.message ?: "Кодът не можа да бъде изпратен.") }
            }
        }
    }

    private fun verifyLoginCode(challenge: CompanyAccountApi.OtpChallenge, code: String) {
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.verifyLoginOtp(applicationContext, challenge.id, code)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    openProfileAndFinish()
                }.onFailure { error -> setStatus(error.message ?: "Кодът не е приет.") }
            }
        }
    }

    private fun showOtpDialog(
        challenge: CompanyAccountApi.OtpChallenge,
        dialogTitle: String,
        verify: (String) -> Unit,
    ) {
        val codeInput = EditText(this).apply {
            hint = "Шестцифрен код"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            if (challenge.debugCode.isNotBlank()) setText(challenge.debugCode)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(dialogTitle)
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

    private fun createCompany() {
        val activation = CompanyLicenseStore.loadValid(this)
        val organizationName = organizationInput.text?.toString().orEmpty().trim()
        if (activation == null) {
            setStatus("Лицензът липсва или е изтекъл.")
            return
        }
        if (organizationName.isBlank()) {
            setStatus("Въведи име на фирмата.")
            return
        }
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.createCompany(
                applicationContext,
                organizationName,
                eikInput.text?.toString().orEmpty(),
                activation.token,
            )
            runOnUiThread {
                showLoading(false)
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    CompanyLicenseStore.clear(applicationContext)
                    Toast.makeText(this, "Фирмата е създадена. Текущият профил е собственик.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(MainActivity.EXTRA_OPEN_REGISTRATION, true)
                    })
                    finish()
                }.onFailure { error -> setStatus(error.message ?: "Неуспешно създаване на фирма.") }
            }
        }
    }

    private fun openProfileAndFinish() {
        startActivity(Intent(this, ProfileEditorActivity::class.java))
        finish()
    }

    private fun showLoading(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
        submitButton.isEnabled = !show
        smsButton.isEnabled = !show
        switchModeButton.isEnabled = !show
        licenseButton.isEnabled = !show
        nameInput.isEnabled = !show
        emailInput.isEnabled = !show
        phoneInput.isEnabled = !show
        organizationInput.isEnabled = !show
        eikInput.isEnabled = !show
    }

    private fun setStatus(value: String) {
        statusText.text = value
    }

    companion object {
        const val EXTRA_MODE = "company_account_mode"
        const val MODE_PROFILE = "profile"
        const val MODE_LOGIN = "login"
        const val MODE_REGISTER = "register"
        const val MODE_CREATE_COMPANY = "create_company"
    }
}