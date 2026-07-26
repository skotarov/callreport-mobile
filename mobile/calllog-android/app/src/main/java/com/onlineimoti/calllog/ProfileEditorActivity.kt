package com.onlineimoti.calllog

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import java.util.concurrent.Executors

/**
 * Displays the authenticated profile and replaces email/phone only after the
 * newly entered destination has been confirmed with its own OTP code.
 */
internal class ProfileEditorActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var nameText: TextView
    private lateinit var emailInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var emailStateText: TextView
    private lateinit var phoneStateText: TextView
    private lateinit var emailButton: MaterialButton
    private lateinit var phoneButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar

    private var profile: CompanySessionStore.Snapshot? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        title = "Профил"
        setContentView(createContent())
        profile = CompanySessionStore.load(this)
        renderProfile(overwriteInputs = true)
        refreshFromServer()
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

        val root = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(column)

        column.addView(TextView(this).apply {
            text = "Данни на профила"
            textSize = 24f
        })
        column.addView(TextView(this).apply {
            text = "Новият имейл или телефон заменя стария едва след правилния код за потвърждение."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(16))
        })

        nameText = TextView(this).apply { textSize = 17f }
        column.addView(nameText)

        emailInput = EditText(this).apply {
            hint = "Нов имейл"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            addTextChangedListener(profileWatcher)
        }
        column.addView(emailInput, params(14))
        emailStateText = TextView(this).apply { textSize = 14f }
        column.addView(emailStateText)
        emailButton = MaterialButton(this).apply {
            setOnClickListener { requestReplacement("email") }
        }
        column.addView(emailButton, params(8))

        phoneInput = EditText(this).apply {
            hint = "Нов телефон"
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            addTextChangedListener(profileWatcher)
        }
        column.addView(phoneInput, params(16))
        phoneStateText = TextView(this).apply { textSize = 14f }
        column.addView(phoneStateText)
        phoneButton = MaterialButton(this).apply {
            setOnClickListener { requestReplacement("sms") }
        }
        column.addView(phoneButton, params(8))

        progress = ProgressBar(this).apply { visibility = View.GONE }
        column.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16)
        })
        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, 0)
        }
        column.addView(statusText)
        return root
    }

    private val profileWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderActions()
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun refreshFromServer() {
        val config = ConfigStore.load(this)
        if (config.accessToken.isBlank()) {
            setStatus("Няма активен вход в профил.")
            renderActions()
            return
        }
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.refreshProfile(applicationContext)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    profile = CompanySessionStore.load(this)
                    renderProfile(overwriteInputs = true)
                    setStatus("")
                }.onFailure { error ->
                    setStatus(error.message ?: "Профилът не можа да бъде зареден от сървъра.")
                }
            }
        }
    }

    private fun renderProfile(overwriteInputs: Boolean) {
        val current = profile
        nameText.text = "Име: ${current?.userName?.ifBlank { "Без име" } ?: "Зарежда се…"}"
        if (current != null && overwriteInputs) {
            emailInput.setText(current.userEmail)
            phoneInput.setText(current.userPhone)
        }
        emailStateText.text = when {
            current == null -> "Текущ имейл: зарежда се…"
            current.emailVerified -> "Текущ имейл: ${current.userEmail} · потвърден"
            else -> "Текущ имейл: ${current.userEmail.ifBlank { "няма" }} · непотвърден"
        }
        phoneStateText.text = when {
            current == null -> "Текущ телефон: зарежда се…"
            current.phoneVerified -> "Текущ телефон: ${current.userPhone} · потвърден"
            else -> "Текущ телефон: ${current.userPhone.ifBlank { "няма" }} · непотвърден"
        }
        renderActions()
    }

    private fun renderActions() {
        val current = profile
        val enteredEmail = emailInput.text?.toString().orEmpty().trim()
        val enteredPhone = phoneInput.text?.toString().orEmpty().trim()
        val emailChanged = current != null && enteredEmail.isNotBlank() && enteredEmail != current.userEmail
        val phoneChanged = current != null && enteredPhone.isNotBlank() && enteredPhone != current.userPhone

        emailButton.text = if (emailChanged) "Потвърди новия имейл" else "Въведи различен имейл"
        phoneButton.text = if (phoneChanged) "Потвърди новия телефон" else "Въведи различен телефон"
        emailButton.isEnabled = !loading && emailChanged
        phoneButton.isEnabled = !loading && phoneChanged
    }

    private fun requestReplacement(channel: String) {
        val value = if (channel == "email") {
            emailInput.text?.toString().orEmpty().trim()
        } else {
            phoneInput.text?.toString().orEmpty().trim()
        }
        if (value.isBlank()) return
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.requestContactOtp(applicationContext, channel, value)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { challenge ->
                    showOtpDialog(
                        challenge = challenge,
                        title = if (channel == "email") "Потвърди новия имейл" else "Потвърди новия телефон",
                    ) { code -> verifyReplacement(challenge, code, channel) }
                }.onFailure { error ->
                    setStatus(error.message ?: "Кодът не можа да бъде изпратен.")
                }
            }
        }
    }

    private fun verifyReplacement(
        challenge: CompanyAccountApi.OtpChallenge,
        code: String,
        channel: String,
    ) {
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.verifyContactOtp(applicationContext, challenge.id, code)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { user ->
                    CompanyAccountApi.applyProfileUser(applicationContext, user)
                    profile = CompanySessionStore.load(this)
                    renderProfile(overwriteInputs = true)
                    setStatus(if (channel == "email") "Имейлът е сменен и потвърден." else "Телефонът е сменен и потвърден.")
                }.onFailure { error ->
                    setStatus(error.message ?: "Кодът не е приет. Старият контакт остава непроменен.")
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
            .setMessage("Кодът е изпратен до ${challenge.destinationHint}. Старият контакт остава активен до успешно потвърждение.")
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

    private fun showLoading(show: Boolean) {
        loading = show
        progress.visibility = if (show) View.VISIBLE else View.GONE
        emailInput.isEnabled = !show
        phoneInput.isEnabled = !show
        renderActions()
    }

    private fun setStatus(value: String) {
        statusText.text = value
    }
}
