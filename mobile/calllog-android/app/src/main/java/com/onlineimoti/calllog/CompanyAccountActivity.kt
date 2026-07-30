package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** OTP profile registration/login and company creation are intentionally separate flows. */
class CompanyAccountActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui by lazy {
        CompanyAccountUi(
            activity = this,
            onSubmit = ::submitPrimary,
            onSms = ::submitSms,
            onSwitchMode = ::switchMode,
            onOpenLicense = { startActivity(Intent(this, CompanyLicenseActivity::class.java)) },
            onVerifyEmail = { requestContactVerification("email") },
            onVerifyPhone = { requestContactVerification("sms") },
            onLogout = ::logout,
        )
    }

    private var mode: String = MODE_LOGIN
    private var profileRefreshStarted = false

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
        setContentView(ui.createContent())
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

    private fun renderMode() {
        val profile = CompanySessionStore.load(this)
        if ((mode == MODE_PROFILE || mode == MODE_CREATE_COMPANY) && profile == null) mode = MODE_LOGIN
        ui.render(
            mode = mode,
            hasBaseUrl = ConfigStore.load(this).baseUrl.isNotBlank(),
            hasActivation = CompanyLicenseStore.loadValid(this) != null,
            profile = profile,
        )
    }

    private fun switchMode() {
        mode = when (mode) {
            MODE_PROFILE -> MODE_CREATE_COMPANY
            MODE_CREATE_COMPANY -> MODE_PROFILE
            MODE_REGISTER -> MODE_LOGIN
            else -> MODE_REGISTER
        }
        if (mode == MODE_LOGIN || mode == MODE_REGISTER) ui.clearProfileInputs()
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
        val name = ui.registrationName()
        val email = ui.emailOrIdentifier()
        val phone = ui.phone()
        if (name.isBlank() || email.isBlank() || phone.isBlank()) {
            ui.setStatus("Въведи име, имейл и телефон.")
            return
        }
        ui.showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.requestRegistrationOtp(applicationContext, email, phone, name, channel)
            runOnUiThread {
                ui.showLoading(false)
                result.onSuccess { challenge ->
                    ui.showOtpDialog(challenge, "Потвърждение на профила") { code ->
                        verifyRegistrationCode(challenge, code)
                    }
                }.onFailure { error -> ui.setStatus(error.message ?: "Кодът не можа да бъде изпратен.") }
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
                        ui.setStatus("Профилът е създаден и двата контакта са потвърдени.")
                        renderMode()
                        return@onSuccess
                    }
                    val next = if (verified.user.emailVerified) {
                        "Имейлът е потвърден. Сега изпрати код със SMS."
                    } else {
                        "Телефонът е потвърден. Сега изпрати код по имейл."
                    }
                    ui.setStatus(next)
                }.onFailure { error -> ui.setStatus(error.message ?: "Кодът не е приет.") }
            }
        }
    }

    private fun requestLoginCode(channel: String) {
        val identifier = ui.emailOrIdentifier()
        if (identifier.isBlank()) {
            ui.setStatus("Въведи имейл или телефон.")
            return
        }
        ui.showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.requestLoginOtp(applicationContext, identifier, channel)
            runOnUiThread {
                ui.showLoading(false)
                result.onSuccess { challenge ->
                    ui.showOtpDialog(challenge, "Вход в профил") { code -> verifyLoginCode(challenge, code) }
                }.onFailure { error -> ui.setStatus(error.message ?: "Кодът не можа да бъде изпратен.") }
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
                    ui.setStatus("Успешен вход. Новият ключ замени предишния.")
                    renderMode()
                }.onFailure { error -> ui.setStatus(error.message ?: "Кодът не е приет.") }
            }
        }
    }

    private fun requestContactVerification(channel: String) {
        val value = if (channel == "email") ui.emailOrIdentifier() else ui.phone()
        if (value.isBlank()) {
            ui.setStatus(if (channel == "email") "Въведи имейл." else "Въведи телефон.")
            return
        }
        ui.showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.requestContactOtp(applicationContext, channel, value)
            runOnUiThread {
                ui.showLoading(false)
                result.onSuccess { challenge ->
                    val dialogTitle = if (channel == "email") "Потвърди имейла" else "Потвърди телефона"
                    ui.showOtpDialog(challenge, dialogTitle) { code -> verifyContactCode(challenge, code) }
                }.onFailure { error -> ui.setStatus(error.message ?: "Кодът не можа да бъде изпратен.") }
            }
        }
    }

    private fun verifyContactCode(challenge: CompanyAccountApi.OtpChallenge, code: String) {
        executor.execute {
            val result = CompanyAccountApi.verifyContactOtp(applicationContext, challenge.id, code)
            runOnUiThread {
                result.onSuccess { user ->
                    CompanyAccountApi.applyProfileUser(applicationContext, user)
                    ui.setStatus("Контактът е потвърден.")
                    renderMode()
                }.onFailure { error -> ui.setStatus(error.message ?: "Кодът не е приет.") }
            }
        }
    }

    private fun createCompany() {
        val activation = CompanyLicenseStore.loadValid(this)
        val organizationName = ui.organizationName()
        if (activation == null) {
            ui.setStatus("Лицензът липсва или е изтекъл.")
            return
        }
        if (organizationName.isBlank()) {
            ui.setStatus("Въведи име на фирмата.")
            return
        }
        ui.showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.createCompany(
                applicationContext,
                organizationName,
                ui.eik(),
                activation.token,
            )
            runOnUiThread {
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    CompanyLicenseStore.clear(applicationContext)
                    ui.showLoading(false)
                    mode = MODE_PROFILE
                    ui.setStatus("Фирмата е създадена и е добавена към профила.")
                    renderMode()
                }.onFailure { error ->
                    ui.showLoading(false)
                    ui.setStatus(error.message ?: "Неуспешно създаване на фирма.")
                }
            }
        }
    }

    private fun logout() {
        ui.showLoading(true)
        executor.execute {
            CompanyAccountApi.logout(applicationContext)
            CompanyAccountApi.clearSession(applicationContext)
            runOnUiThread {
                ui.showLoading(false)
                profileRefreshStarted = false
                mode = MODE_LOGIN
                ui.clearLoginContacts()
                ui.setStatus("Излезе от профила. Ключът за връзка със сървъра е изтрит.")
                renderMode()
            }
        }
    }

    private fun refreshProfileFromServer() {
        if (profileRefreshStarted || CompanySessionStore.load(this) == null) return
        profileRefreshStarted = true
        executor.execute {
            val result = CompanyAccountApi.refreshProfile(applicationContext)
            runOnUiThread {
                result.onSuccess {
                    CompanyAccountApi.applySession(applicationContext, it)
                    renderMode()
                }
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "company_account_mode"
        const val MODE_PROFILE = "profile"
        const val MODE_LOGIN = "login"
        const val MODE_REGISTER = "register"
        const val MODE_CREATE_COMPANY = "create_company"
    }
}
