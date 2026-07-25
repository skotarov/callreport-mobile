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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Profile registration/login and company creation are intentionally separate flows. */
class CompanyAccountActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var titleText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var submitButton: MaterialButton
    private lateinit var switchModeButton: MaterialButton
    private lateinit var licenseButton: MaterialButton
    private lateinit var nameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var organizationInput: EditText
    private lateinit var eikInput: EditText

    private var mode: String = MODE_LOGIN

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
        passwordInput = editInput("Парола (поне 10 символа)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        organizationInput = editInput("Име на фирма / организация", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        eikInput = editInput("ЕИК / Булстат (незадължително)", InputType.TYPE_CLASS_NUMBER)

        column.addView(nameInput)
        column.addView(emailInput, verticalParams(8))
        column.addView(passwordInput, verticalParams(8))
        column.addView(organizationInput, verticalParams(8))
        column.addView(eikInput, verticalParams(8))

        submitButton = MaterialButton(this).apply { setOnClickListener { submit() } }
        column.addView(submitButton, verticalParams(16))

        switchModeButton = MaterialButton(this).apply { setOnClickListener { switchMode() } }
        column.addView(switchModeButton, verticalParams(8))

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
        val hasBaseUrl = ConfigStore.load(this).baseUrl.isNotBlank()
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
            viewingProfile -> {
                val user = profile?.userName.orEmpty().ifBlank { "текущия профил" }
                "Влезли сте като $user. Профилът е отделен от фирмите и може да участва в много фирми с различни роли."
            }
            creatingProfile -> "Създай личния си профил само с име, имейл и парола. Фирма може да добавиш отделно след вход."
            creatingCompany && activation == null -> "Фирмата се създава отделно към влезлия профил. За нея е необходим потвърден лиценз."
            creatingCompany -> "Лицензът е потвърден. Въведи данните на новата фирма."
            else -> "Влез с имейл и парола. След това ще видиш всички фирми, към които профилът има достъп."
        }

        nameInput.visibility = if (creatingProfile) View.VISIBLE else View.GONE
        emailInput.visibility = if (loggingIn || creatingProfile) View.VISIBLE else View.GONE
        passwordInput.visibility = if (loggingIn || creatingProfile) View.VISIBLE else View.GONE
        organizationInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        eikInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        submitButton.visibility = if (viewingProfile) View.GONE else View.VISIBLE
        submitButton.text = when {
            creatingProfile -> "Създай профил"
            creatingCompany -> "Създай фирма"
            else -> "Вход"
        }
        switchModeButton.text = when {
            viewingProfile -> "Създай фирма"
            creatingCompany -> "Назад към профила"
            creatingProfile -> "Вече имам профил"
            else -> "Създай профил"
        }
        licenseButton.visibility = if (creatingCompany && activation == null) View.VISIBLE else View.GONE
        submitButton.isEnabled = hasBaseUrl && when {
            creatingCompany -> profile != null && activation != null
            else -> true
        }

        when {
            !hasBaseUrl -> setStatus("Първо настрой сървърния адрес от Настройки → Профил и фирми.")
            creatingCompany && activation == null -> setStatus("Първо купи или възстанови лиценз за новата фирма.")
            viewingProfile -> setStatus("Фирмите и ролите се показват в секцията „Включени фирми“.")
            else -> setStatus("")
        }
    }

    private fun switchMode() {
        mode = when (mode) {
            MODE_PROFILE -> MODE_CREATE_COMPANY
            MODE_CREATE_COMPANY -> MODE_PROFILE
            MODE_REGISTER -> MODE_LOGIN
            else -> MODE_REGISTER
        }
        renderMode()
    }

    private fun submit() {
        when (mode) {
            MODE_REGISTER -> registerProfile()
            MODE_CREATE_COMPANY -> createCompany()
            else -> login()
        }
    }

    private fun registerProfile() {
        val name = nameInput.text?.toString().orEmpty().trim()
        val email = emailInput.text?.toString().orEmpty().trim()
        val password = passwordInput.text?.toString().orEmpty()
        if (name.isBlank() || email.isBlank() || password.isBlank()) {
            setStatus("Въведи име, имейл и парола.")
            return
        }
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.registerProfile(applicationContext, email, password, name)
            runOnUiThread {
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    showLoading(false)
                    mode = MODE_PROFILE
                    setStatus("Профилът е създаден. Сега можеш да създадеш фирма или да приемеш покана.")
                    renderMode()
                }.onFailure { error ->
                    showLoading(false)
                    setStatus(error.message ?: "Неуспешно създаване на профил.")
                }
            }
        }
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

    private fun login() {
        val email = emailInput.text?.toString().orEmpty().trim()
        val password = passwordInput.text?.toString().orEmpty()
        if (email.isBlank() || password.isBlank()) {
            setStatus("Въведи имейл и парола.")
            return
        }
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.login(applicationContext, email, password)
            runOnUiThread {
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    showLoading(false)
                    mode = MODE_PROFILE
                    setStatus("Успешен вход в профила.")
                    renderMode()
                }.onFailure { error ->
                    showLoading(false)
                    setStatus(error.message ?: "Неуспешен вход.")
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
        submitButton.isEnabled = !show
        switchModeButton.isEnabled = !show
        licenseButton.isEnabled = !show
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
