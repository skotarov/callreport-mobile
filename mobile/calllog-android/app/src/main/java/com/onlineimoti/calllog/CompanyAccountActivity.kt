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

/** One OTP flow enters an existing profile or creates a new one. Company creation stays separate. */
class CompanyAccountActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var titleText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var submitButton: MaterialButton
    private lateinit var licenseButton: MaterialButton
    private lateinit var identifierInput: EditText
    private lateinit var organizationInput: EditText
    private lateinit var eikInput: EditText

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
        ) {
            MODE_CREATE_COMPANY
        } else {
            MODE_LOGIN
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

        identifierInput = input("Телефон или имейл", InputType.TYPE_CLASS_TEXT)
        organizationInput = input(
            "Име на фирма / организация",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
        )
        eikInput = input("ЕИК / Булстат (незадължително)", InputType.TYPE_CLASS_NUMBER)
        column.addView(identifierInput)
        column.addView(organizationInput, params(8))
        column.addView(eikInput, params(8))

        submitButton = MaterialButton(this).apply { setOnClickListener { submitPrimary() } }
        column.addView(submitButton, params(16))
        licenseButton = MaterialButton(this).apply {
            text = "Купи / възстанови лиценз за фирма"
            setOnClickListener {
                startActivity(Intent(this@CompanyAccountActivity, CompanyLicenseActivity::class.java))
            }
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
        val creatingCompany = mode == MODE_CREATE_COMPANY

        titleText.text = if (creatingCompany) "Създай фирма" else "Вход или създаване на профил"
        descriptionText.text = when {
            creatingCompany && activation == null ->
                "За нова фирма е необходим потвърден лиценз. След създаването текущият профил става собственик."
            creatingCompany ->
                "Въведи данните на новата фирма. Текущият профил ще бъде добавен като собственик."
            else ->
                "Въведи само телефон или имейл. Ако профилът съществува, ще влезеш в него. Ако не съществува, ще бъде създаден автоматично след правилния код. Име и парола не са нужни."
        }

        identifierInput.visibility = if (creatingCompany) View.GONE else View.VISIBLE
        organizationInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        eikInput.visibility = if (creatingCompany) View.VISIBLE else View.GONE
        submitButton.text = if (creatingCompany) "Създай фирма" else "Изпрати код"
        licenseButton.visibility = if (creatingCompany && activation == null) View.VISIBLE else View.GONE
        submitButton.isEnabled = hasBaseUrl && (!creatingCompany || activation != null)

        when {
            !hasBaseUrl -> setStatus("Първо настрой сървърния адрес от Настройки → Профил.")
            creatingCompany && CompanySessionStore.load(this) == null -> setStatus("Първо влез в профила.")
            creatingCompany && activation == null -> setStatus("Първо купи или възстанови лиценз за новата фирма.")
            else -> setStatus("")
        }
    }

    private fun submitPrimary() {
        if (mode == MODE_CREATE_COMPANY) createCompany() else requestAccessCode()
    }

    private fun requestAccessCode() {
        val target = ProfileAccessInput.parse(identifierInput.text?.toString().orEmpty())
        if (target == null) {
            setStatus("Въведи валиден телефон или имейл.")
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
                    val dialogTitle = if (target.channel == "email") {
                        "Потвърди имейла"
                    } else {
                        "Потвърди телефона"
                    }
                    showOtpDialog(challenge, dialogTitle) { code ->
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
        progress.visibility = if (show) View.VISIBLE else View.GONE
        submitButton.isEnabled = !show
        licenseButton.isEnabled = !show
        identifierInput.isEnabled = !show
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
        // Kept as an alias so older intents still open the single access flow.
        const val MODE_REGISTER = "register"
        const val MODE_CREATE_COMPANY = "create_company"
    }
}
