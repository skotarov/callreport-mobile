package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One OTP flow enters an existing profile or creates a new one. Company creation stays separate. */
class CompanyAccountActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var ui: CompanyAccountScreen
    private var mode: String = MODE_LOGIN

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        val requestedMode = intent.getStringExtra(EXTRA_MODE)
        if (
            requestedMode == MODE_PROFILE &&
            ConfigStore.load(this).accessToken.isNotBlank() &&
            CompanySessionStore.load(this) != null
        ) {
            startActivity(Intent(this, ProfileEditorActivity::class.java))
            finish()
            return
        }
        mode = if (
            requestedMode == MODE_CREATE_COMPANY &&
            CompanySessionStore.load(this) != null
        ) MODE_CREATE_COMPANY else MODE_LOGIN
        title = if (mode == MODE_CREATE_COMPANY) "Създай фирма" else "Профил"
        ui = CompanyAccountScreen(this)
        setContentView(
            ui.create(
                onSms = { requestAccessCode(ui.phoneInput.text?.toString().orEmpty(), "sms") },
                onEmail = { requestAccessCode(ui.emailInput.text?.toString().orEmpty(), "email") },
                onCreateCompany = ::createCompany,
                onLicense = {
                    startActivity(Intent(this, CompanyLicenseActivity::class.java))
                },
            ),
        )
        renderMode()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun renderMode() {
        val hasBaseUrl = ConfigStore.load(this).baseUrl.isNotBlank()
        val activation = CompanyLicenseStore.loadValid(this)
        val creatingCompany = mode == MODE_CREATE_COMPANY

        ui.titleText.text = if (creatingCompany) "Създай фирма" else "Вход или създаване на профил"
        ui.descriptionText.text = when {
            creatingCompany && activation == null ->
                "За нова фирма е необходим потвърден лиценз. След създаването текущият профил става собственик."
            creatingCompany ->
                "Въведи данните на новата фирма. Текущият профил ще бъде добавен като собственик."
            else ->
                "Избери телефон или имейл и натисни съответния бутон. Ако профилът не съществува, ще бъде създаден автоматично след правилния код. Име и парола не са нужни."
        }

        val accessVisibility = if (creatingCompany) View.GONE else View.VISIBLE
        ui.phoneInput.visibility = accessVisibility
        ui.smsButton.visibility = accessVisibility
        ui.alternativeText.visibility = accessVisibility
        ui.emailInput.visibility = accessVisibility
        ui.emailButton.visibility = accessVisibility
        ui.organizationInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        ui.eikInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        ui.companyButton.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        ui.licenseButton.visibility = if (creatingCompany && activation == null) View.VISIBLE else View.GONE
        ui.smsButton.isEnabled = hasBaseUrl
        ui.emailButton.isEnabled = hasBaseUrl
        ui.companyButton.isEnabled = hasBaseUrl && activation != null

        when {
            !hasBaseUrl -> setStatus("Първо настрой сървърния адрес от Настройки → Профил.")
            creatingCompany && CompanySessionStore.load(this) == null -> setStatus("Първо влез в профила.")
            creatingCompany && activation == null -> setStatus("Първо купи или възстанови лиценз за новата фирма.")
            else -> setStatus("")
        }
    }

    private fun requestAccessCode(rawIdentifier: String, expectedChannel: String) {
        val target = ProfileAccessInput.parse(rawIdentifier)
        if (target == null || target.channel != expectedChannel) {
            setStatus(
                if (expectedChannel == "sms") "Въведи валиден телефонен номер."
                else "Въведи валиден имейл адрес.",
            )
            return
        }
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.requestLoginOtp(
                applicationContext,
                target.identifier,
                target.channel,
            )
            runOnUiThread {
                showLoading(false)
                result.onSuccess { challenge ->
                    val title = if (target.channel == "email") "Потвърди имейла" else "Потвърди телефона"
                    ProfileOtpDialog.show(this, challenge, title) { code ->
                        verifyAccessCode(challenge, code)
                    }
                }.onFailure { error ->
                    setStatus(error.message ?: "Кодът не можа да бъде изпратен.")
                }
            }
        }
    }

    private fun verifyAccessCode(challenge: CompanyAccountApi.OtpChallenge, code: String) {
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.verifyLoginOtp(applicationContext, challenge.id, code)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    Toast.makeText(
                        this,
                        "Профилът е потвърден и новият токън е записан автоматично.",
                        Toast.LENGTH_LONG,
                    ).show()
                    openProfileAndFinish()
                }.onFailure { error ->
                    setStatus(error.message ?: "Кодът не е приет.")
                }
            }
        }
    }

    private fun createCompany() {
        val activation = CompanyLicenseStore.loadValid(this)
        val organizationName = ui.organizationInput.text?.toString().orEmpty().trim()
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
                ui.eikInput.text?.toString().orEmpty(),
                activation.token,
            )
            runOnUiThread {
                showLoading(false)
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    CompanyLicenseStore.clear(applicationContext)
                    Toast.makeText(
                        this,
                        "Фирмата е създадена. Текущият профил е собственик.",
                        Toast.LENGTH_LONG,
                    ).show()
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(MainActivity.EXTRA_OPEN_REGISTRATION, true)
                    })
                    finish()
                }.onFailure { error ->
                    setStatus(error.message ?: "Неуспешно създаване на фирма.")
                }
            }
        }
    }

    private fun openProfileAndFinish() {
        startActivity(Intent(this, ProfileEditorActivity::class.java))
        finish()
    }

    private fun showLoading(show: Boolean) {
        ui.showLoading(show)
    }

    private fun setStatus(value: String) {
        ui.statusText.text = value
    }

    companion object {
        const val EXTRA_MODE = "company_account_mode"
        const val MODE_PROFILE = "profile"
        const val MODE_LOGIN = "login"
        // Kept as an alias so older intents still open the single access flow.
        const val MODE_REGISTER = "register"
        const val MODE_CREATE_COMPANY = "create_company"
    }
}
