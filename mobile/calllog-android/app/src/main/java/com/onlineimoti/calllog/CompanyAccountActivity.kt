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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Account login and initial company creation. One account may belong to many companies. */
class CompanyAccountActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var titleText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var serverUrlButton: MaterialButton
    private lateinit var submitButton: MaterialButton
    private lateinit var switchModeButton: MaterialButton
    private lateinit var licenseButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var nameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var organizationInput: EditText
    private lateinit var eikInput: EditText
    private lateinit var registrationFields: LinearLayout

    private var mode: String = MODE_LOGIN

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        val requestedMode = intent.getStringExtra(EXTRA_MODE)
        mode = when {
            requestedMode == MODE_REGISTER -> MODE_REGISTER
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

        serverUrlButton = MaterialButton(this).apply {
            isAllCaps = false
            setOnClickListener { openServerSettings() }
        }
        column.addView(serverUrlButton)

        nameInput = editInput("Твоето име", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        emailInput = editInput("Имейл", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        passwordInput = editInput("Парола (поне 10 символа)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        organizationInput = editInput("Име на първата фирма / организация", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        eikInput = editInput("ЕИК / Булстат (незадължително)", InputType.TYPE_CLASS_NUMBER)

        registrationFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        registrationFields.addView(nameInput, verticalParams())
        registrationFields.addView(organizationInput, verticalParams(8))
        registrationFields.addView(eikInput, verticalParams(8))
        column.addView(registrationFields, verticalParams(8))
        column.addView(emailInput, verticalParams(8))
        column.addView(passwordInput, verticalParams(8))

        submitButton = MaterialButton(this).apply { setOnClickListener { submit() } }
        column.addView(submitButton, verticalParams(16))

        switchModeButton = MaterialButton(this).apply { setOnClickListener { switchMode() } }
        column.addView(switchModeButton, verticalParams(8))

        licenseButton = MaterialButton(this).apply {
            text = "Купи / възстанови лиценз"
            setOnClickListener { startActivity(Intent(this@CompanyAccountActivity, CompanyLicenseActivity::class.java)) }
        }
        column.addView(licenseButton, verticalParams(8))

        settingsButton = MaterialButton(this).apply {
            text = "Отвори профил и фирми"
            setOnClickListener {
                startActivity(Intent(this@CompanyAccountActivity, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_REGISTRATION, true)
                })
            }
        }
        column.addView(settingsButton, verticalParams(8))

        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }
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
        val profile = CompanySessionStore.load(this)
        if (mode == MODE_PROFILE && profile == null) {
            mode = MODE_LOGIN
        }

        val creatingProfile = mode == MODE_REGISTER
        val viewingProfile = mode == MODE_PROFILE

        serverUrlButton.text = if (hasBaseUrl) {
            "Промени сървърния адрес"
        } else {
            "1. Настрой сървърния адрес"
        }
        titleText.text = when {
            viewingProfile -> "Профил и фирми"
            creatingProfile -> "Създай профил и първа фирма"
            else -> "Вход в профил"
        }
        descriptionText.text = when {
            viewingProfile -> {
                val user = profile?.userName.orEmpty().ifBlank { "текущия профил" }
                "Влезли сте като $user. Един профил може да бъде включен в много фирми, като ролята може да е различна във всяка фирма."
            }
            creatingProfile && activation == null -> {
                "За създаване на профил и първа фирма е нужен потвърден еднократен лиценз. След това профилът може да бъде включен и в други фирми."
            }
            creatingProfile -> {
                "Лицензът е потвърден. Попълни данните, за да създадеш профила и първата фирма към него."
            }
            else -> {
                "Първо настрой сървърния адрес, после влез еднократно в профила. След вход приложението зарежда всички фирми и ролята във всяка от тях."
            }
        }

        registrationFields.visibility = if (creatingProfile) View.VISIBLE else View.GONE
        emailInput.visibility = if (viewingProfile) View.GONE else View.VISIBLE
        passwordInput.visibility = if (viewingProfile) View.GONE else View.VISIBLE
        submitButton.visibility = if (viewingProfile) View.GONE else View.VISIBLE
        submitButton.text = if (creatingProfile) "Създай профил" else "Вход"
        switchModeButton.text = when {
            viewingProfile -> "Вход с друг профил"
            creatingProfile -> "Вече имам профил"
            else -> "Създай профил и първа фирма"
        }
        licenseButton.visibility = if (creatingProfile && activation != null) View.GONE else View.VISIBLE
        submitButton.isEnabled = !viewingProfile && hasBaseUrl && (!creatingProfile || activation != null)

        when {
            viewingProfile -> setStatus("Фирмите и ролите се показват отделно в секцията „Включени фирми“.")
            !hasBaseUrl -> setStatus("Първо настрой сървърния адрес от бутона по-горе.")
            creatingProfile && activation == null -> setStatus("Първо купи или възстанови лиценза от Google Play.")
            else -> setStatus("")
        }
    }

    private fun openServerSettings() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_SERVER, true)
        })
    }

    private fun switchMode() {
        mode = when (mode) {
            MODE_PROFILE -> MODE_LOGIN
            MODE_REGISTER -> MODE_LOGIN
            else -> MODE_REGISTER
        }
        renderMode()
    }

    private fun submit() {
        val email = emailInput.text?.toString().orEmpty().trim()
        val password = passwordInput.text?.toString().orEmpty()
        if (email.isBlank() || password.isBlank()) {
            setStatus("Въведи имейл и парола.")
            return
        }
        if (mode == MODE_REGISTER) {
            val activation = CompanyLicenseStore.loadValid(this)
            if (activation == null) {
                setStatus("Лицензът липсва или е изтекъл. Купи или възстанови покупката отново.")
                return
            }
            if (nameInput.text?.toString().orEmpty().trim().isBlank() || organizationInput.text?.toString().orEmpty().trim().isBlank()) {
                setStatus("Въведи твоето име и име на първата фирма.")
                return
            }
            registerCompany(email, password, activation)
        } else {
            login(email, password)
        }
    }

    private fun registerCompany(email: String, password: String, activation: CompanyLicenseStore.Activation) {
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.register(
                context = applicationContext,
                email = email,
                password = password,
                displayName = nameInput.text?.toString().orEmpty(),
                organizationName = organizationInput.text?.toString().orEmpty(),
                organizationEik = eikInput.text?.toString().orEmpty(),
                activationToken = activation.token,
            )
            runOnUiThread {
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    CompanyLicenseStore.clear(applicationContext)
                    showLoading(false)
                    setStatus("Профилът и първата фирма са създадени. Достъпните фирми ще бъдат заредени автоматично.")
                    openHome()
                }.onFailure { error ->
                    showLoading(false)
                    setStatus(error.message ?: "Неуспешно създаване на профил.")
                }
            }
        }
    }

    private fun login(email: String, password: String) {
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.login(applicationContext, email, password)
            runOnUiThread {
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    showLoading(false)
                    setStatus("Успешен вход в профила. Достъпните фирми ще бъдат заредени автоматично.")
                    openHome()
                }.onFailure { error ->
                    showLoading(false)
                    setStatus(error.message ?: "Неуспешен вход.")
                }
            }
        }
    }

    private fun openHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun showLoading(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
        serverUrlButton.isEnabled = !show
        submitButton.isEnabled = !show
        switchModeButton.isEnabled = !show
        licenseButton.isEnabled = !show
        settingsButton.isEnabled = !show
    }

    private fun setStatus(value: String) {
        statusText.text = value
    }

    companion object {
        const val EXTRA_MODE = "company_account_mode"
        const val MODE_PROFILE = "profile"
        const val MODE_LOGIN = "login"
        const val MODE_REGISTER = "register"
    }
}