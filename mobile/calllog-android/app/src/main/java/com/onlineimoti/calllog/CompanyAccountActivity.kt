package com.onlineimoti.calllog

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** OTP profile registration/login and company creation are intentionally separate flows. */
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
    private lateinit var verifyEmailButton: MaterialButton
    private lateinit var verifyPhoneButton: MaterialButton
    private lateinit var reloadButton: MaterialButton
    private lateinit var logoutButton: MaterialButton
    private lateinit var nameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var organizationInput: EditText
    private lateinit var eikInput: EditText

    private var mode: String = MODE_LOGIN
    private var profileRefreshInFlight = false
    private var profileAutoRefreshAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        val requestedMode = intent.getStringExtra(EXTRA_MODE)
        mode = when {
            requestedMode == MODE_REGISTER -> MODE_REGISTER
            requestedMode == MODE_CREATE_COMPANY && CompanySessionStore.load(this) != null -> MODE_CREATE_COMPANY
            CompanySessionStore.load(this) != null -> MODE_PROFILE
            else -> MODE_LOGIN
        }
        title = "Профил и фирми"
        setContentView(createContent())
        renderMode()
    }

    override fun onResume() {
        super.onResume()
        renderMode()
        refreshProfileFromServer()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createContent(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun verticalParams(top: Int = 0) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }
        fun editInput(hint: String, inputType: Int): EditText = EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
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

        submitButton = MaterialButton(this).apply { setOnClickListener { submitPrimary() } }
        column.addView(submitButton, verticalParams(16))

        smsButton = MaterialButton(this).apply {
            text = "Изпрати код със SMS"
            setOnClickListener { submitSms() }
        }
        column.addView(smsButton, verticalParams(8))

        verifyEmailButton = MaterialButton(this).apply {
            text = "Потвърди имейла"
            setOnClickListener { requestContactVerification("email") }
        }
        column.addView(verifyEmailButton, verticalParams(8))

        verifyPhoneButton = MaterialButton(this).apply {
            text = "Потвърди телефона"
            setOnClickListener { requestContactVerification("sms") }
        }
        column.addView(verifyPhoneButton, verticalParams(8))

        reloadButton = MaterialButton(this).apply {
            text = "Презареди"
            setOnClickListener { refreshProfileFromServer(manual = true) }
        }
        column.addView(reloadButton, verticalParams(8))

        switchModeButton = MaterialButton(this).apply { setOnClickListener { switchMode() } }
        column.addView(switchModeButton, verticalParams(8))

        logoutButton = MaterialButton(this).apply {
            text = "Излез"
            setOnClickListener { logout() }
        }
        column.addView(logoutButton, verticalParams(8))

        licenseButton = MaterialButton(this).apply {
            text = "Купи / възстанови лиценз за фирма"
            setOnClickListener { startActivity(android.content.Intent(this@CompanyAccountActivity, CompanyLicenseActivity::class.java)) }
        }
        column.addView(licenseButton, verticalParams(8))

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
        val config = ConfigStore.load(this)
        val hasBaseUrl = config.baseUrl.isNotBlank()
        val hasAccessToken = config.accessToken.isNotBlank()
        val activation = CompanyLicenseStore.loadValid(this)
        val profile = CompanySessionStore.load(this)
        if ((mode == MODE_PROFILE || mode == MODE_CREATE_COMPANY) && profile == null) mode = MODE_LOGIN

        val creatingProfile = mode == MODE_REGISTER
        val creatingCompany = mode == MODE_CREATE_COMPANY
        val viewingProfile = mode == MODE_PROFILE
        val loggingIn = mode == MODE_LOGIN

        titleText.text = when {
            viewingProfile -> "Профил"
            creatingProfile -> "Създай профил"
            creatingCompany -> "Създай фирма"
            else -> "Вход в профил"
        }
        descriptionText.text = when {
            viewingProfile -> profileDescription(profile)
            creatingProfile -> "Профилът е с име, имейл и телефон. Имейлът и телефонът се потвърждават с отделни еднократни кодове."
            creatingCompany && activation == null -> "Фирмата се създава отделно към влезлия профил. За нея е необходим потвърден лиценз."
            creatingCompany -> "Лицензът е потвърден. Въведи данните на новата фирма."
            else -> "Въведи имейла или телефона на профила и избери къде да получиш еднократния код. Парола не е нужна."
        }

        nameInput.visibility = if (creatingProfile) View.VISIBLE else View.GONE
        emailInput.visibility = if (loggingIn || creatingProfile || viewingProfile) View.VISIBLE else View.GONE
        phoneInput.visibility = if (creatingProfile || viewingProfile) View.VISIBLE else View.GONE
        organizationInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        eikInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE

        if (loggingIn) emailInput.hint = "Имейл или телефон" else emailInput.hint = "Имейл"
        if (viewingProfile && profile != null) {
            if (!emailInput.hasFocus()) emailInput.setText(profile.userEmail)
            if (!phoneInput.hasFocus()) phoneInput.setText(profile.userPhone)
        }

        submitButton.visibility = if (viewingProfile) View.GONE else View.VISIBLE
        submitButton.text = when {
            creatingProfile -> "Изпрати код по имейл"
            creatingCompany -> "Създай фирма"
            else -> "Изпрати код по имейл"
        }
        smsButton.visibility = if (loggingIn || creatingProfile) View.VISIBLE else View.GONE
        verifyEmailButton.visibility = if (viewingProfile) View.VISIBLE else View.GONE
        verifyPhoneButton.visibility = if (viewingProfile) View.VISIBLE else View.GONE
        verifyEmailButton.text = if (profile?.emailVerified == true) "Имейлът е потвърден" else "Потвърди имейла"
        verifyPhoneButton.text = if (profile?.phoneVerified == true) "Телефонът е потвърден" else "Потвърди телефона"
        verifyEmailButton.isEnabled = viewingProfile && profile?.emailVerified != true
        verifyPhoneButton.isEnabled = viewingProfile && profile?.phoneVerified != true

        reloadButton.visibility = if (hasBaseUrl && hasAccessToken && (viewingProfile || loggingIn)) View.VISIBLE else View.GONE
        reloadButton.isEnabled = !profileRefreshInFlight
        switchModeButton.text = when {
            viewingProfile -> "Създай фирма"
            creatingCompany -> "Назад към профила"
            creatingProfile -> "Вече имам профил"
            else -> "Създай профил"
        }
        logoutButton.visibility = if (viewingProfile || creatingCompany) View.VISIBLE else View.GONE
        licenseButton.visibility = if (creatingCompany && activation == null) View.VISIBLE else View.GONE
        submitButton.isEnabled = hasBaseUrl && when {
            creatingCompany -> profile != null && activation != null
            else -> true
        }
        smsButton.isEnabled = hasBaseUrl

        when {
            !hasBaseUrl -> setStatus("Първо настрой сървърния адрес от Настройки → Профил и фирми.")
            creatingCompany && activation == null -> setStatus("Първо купи или възстанови лиценз за новата фирма.")
            viewingProfile && profile?.profileReady != true -> setStatus("Потвърди и имейла, и телефона, за да е завършен профилът.")
            viewingProfile -> setStatus("При следващ вход сървърът ще издаде нов ключ и ще анулира предишния.")
            else -> setStatus("")
        }
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

    private fun switchMode() {
        mode = when (mode) {
            MODE_PROFILE -> MODE_CREATE_COMPANY
            MODE_CREATE_COMPANY -> MODE_PROFILE
            MODE_REGISTER -> MODE_LOGIN
            else -> MODE_REGISTER
        }
        if (mode == MODE_LOGIN || mode == MODE_REGISTER) {
            nameInput.text?.clear()
            emailInput.text?.clear()
            phoneInput.text?.clear()
        }
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
        executor.execute {
            val result = CompanyAccountApi.verifyRegistrationOtp(applicationContext, challenge.id, code)
            runOnUiThread {
                result.onSuccess { verified ->
                    verified.session?.let { session ->
                        CompanyAccountApi.applySession(applicationContext, session)
                        mode = MODE_PROFILE
                        setStatus("Профилът е създаден и двата контакта са потвърдени.")
                        renderMode()
                        return@onSuccess
                    }
                    val next = if (verified.user.emailVerified) "Имейлът е потвърден. Сега изпрати код със SMS." else "Телефонът е потвърден. Сега изпрати код по имейл."
                    setStatus(next)
                }.onFailure { error ->
                    setStatus(error.message ?: "Кодът не е приет.")
                }
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
        executor.execute {
            val result = CompanyAccountApi.verifyLoginOtp(applicationContext, challenge.id, code)
            runOnUiThread {
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    mode = MODE_PROFILE
                    setStatus("Успешен вход. Новият ключ замени предишния.")
                    renderMode()
                }.onFailure { error ->
                    setStatus(error.message ?: "Кодът не е приет.")
                }
            }
        }
    }

    private fun requestContactVerification(channel: String) {
        val value = if (channel == "email") emailInput.text?.toString().orEmpty().trim() else phoneInput.text?.toString().orEmpty().trim()
        if (value.isBlank()) {
            setStatus(if (channel == "email") "Въведи имейл." else "Въведи телефон.")
            return
        }
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.requestContactOtp(applicationContext, channel, value)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { challenge ->
                    showOtpDialog(challenge, if (channel == "email") "Потвърди имейла" else "Потвърди телефона") { code ->
                        verifyContactCode(challenge, code)
                    }
                }.onFailure { error -> setStatus(error.message ?: "Кодът не можа да бъде изпратен.") }
            }
        }
    }

    private fun verifyContactCode(challenge: CompanyAccountApi.OtpChallenge, code: String) {
        executor.execute {
            val result = CompanyAccountApi.verifyContactOtp(applicationContext, challenge.id, code)
            runOnUiThread {
                result.onSuccess { user ->
                    CompanyAccountApi.applyProfileUser(applicationContext, user)
                    setStatus("Контактът е потвърден.")
                    renderMode()
                }.onFailure { error ->
                    setStatus(error.message ?: "Кодът не е приет.")
                }
            }
        }
    }

    private fun showOtpDialog(
        challenge: CompanyAccountApi.OtpChallenge,
        title: String,
        verify: (String) -> Unit,
    ) {
        val codeInput = EditText(this).apply {
            hint = "Шестцифрен код"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            if (challenge.debugCode.isNotBlank()) setText(challenge.debugCode)
        }
        val dialog = AlertDialog.Builder(this)
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
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    CompanyLicenseStore.clear(applicationContext)
                    showLoading(false)
                    mode = MODE_PROFILE
                    setStatus("Фирмата е създадена и е добавена към профила.")
                    renderMode()
                }.onFailure { error ->
                    showLoading(false)
                    setStatus(error.message ?: "Неуспешно създаване на фирма.")
                }
            }
        }
    }

    private fun logout() {
        showLoading(true)
        executor.execute {
            CompanyAccountApi.logout(applicationContext)
            CompanyAccountApi.clearSession(applicationContext)
            runOnUiThread {
                showLoading(false)
                profileRefreshInFlight = false
                profileAutoRefreshAttempted = false
                mode = MODE_LOGIN
                emailInput.text?.clear()
                phoneInput.text?.clear()
                setStatus("Излезе от профила. Ключът за връзка със сървъра е изтрит.")
                renderMode()
            }
        }
    }

    private fun refreshProfileFromServer(manual: Boolean = false) {
        val config = ConfigStore.load(this)
        if (profileRefreshInFlight || config.baseUrl.isBlank() || config.accessToken.isBlank()) return
        if (!manual && profileAutoRefreshAttempted) return
        if (!manual) profileAutoRefreshAttempted = true

        profileRefreshInFlight = true
        if (manual) {
            showLoading(true)
            setStatus("Презареждам профила и фирмите от сървъра…")
        }
        executor.execute {
            val result = CompanyAccountApi.refreshProfile(applicationContext)
            runOnUiThread {
                profileRefreshInFlight = false
                if (manual) showLoading(false)
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    mode = MODE_PROFILE
                    renderMode()
                    if (manual) setStatus("Профилът и фирмите са презаредени от сървъра.")
                }.onFailure { error ->
                    renderMode()
                    if (manual) setStatus(error.message ?: "Профилът и фирмите не можаха да бъдат презаредени.")
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
        submitButton.isEnabled = !show
        smsButton.isEnabled = !show
        switchModeButton.isEnabled = !show
        licenseButton.isEnabled = !show
        verifyEmailButton.isEnabled = !show
        verifyPhoneButton.isEnabled = !show
        reloadButton.isEnabled = !show
        logoutButton.isEnabled = !show
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
