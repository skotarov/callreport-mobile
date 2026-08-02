package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/**
 * Shows the complete profile form. The display name is saved directly; email and
 * phone are replaced only after the new value passes its own OTP.
 */
internal class ProfileEditorActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var nameInput: EditText
    private lateinit var nameButton: MaterialButton
    private lateinit var emailInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var emailStateText: TextView
    private lateinit var phoneStateText: TextView
    private lateinit var emailButton: MaterialButton
    private lateinit var phoneButton: MaterialButton
    private lateinit var logoutButton: MaterialButton
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
            text = "Профил"
            textSize = 24f
        })
        column.addView(TextView(this).apply {
            text = "Редактирай името, имейла и телефона. Името се записва директно. Новият имейл или телефон заменя стария едва след правилния код за потвърждение."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(16))
        })

        nameInput = EditText(this).apply {
            hint = "Име"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
            addTextChangedListener(profileWatcher)
        }
        column.addView(nameInput)
        nameButton = MaterialButton(this).apply {
            setOnClickListener { saveName() }
        }
        column.addView(nameButton, params(8))

        emailInput = EditText(this).apply {
            hint = "Имейл"
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
            hint = "Телефон"
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
            visibility = View.GONE
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        column.addView(statusText, params(16))

        val errorColor = ContextCompat.getColor(this, R.color.calllog_error)
        logoutButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            setText(R.string.settings_registration_logout)
            setTextColor(errorColor)
            strokeColor = ColorStateList.valueOf(errorColor)
            strokeWidth = dp(1)
            setOnClickListener { logout() }
        }
        column.addView(logoutButton, params(28))
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
        if (current != null && overwriteInputs) {
            nameInput.setText(current.userName)
            emailInput.setText(current.userEmail)
            phoneInput.setText(PhoneNormalizer.display(current.userPhone))
        }
        emailStateText.text = when {
            current == null -> "Текущ имейл: зарежда се…"
            current.emailVerified -> "Текущ имейл: ${current.userEmail} · потвърден"
            else -> "Текущ имейл: ${current.userEmail.ifBlank { "няма" }} · непотвърден"
        }
        val displayPhone = current?.userPhone?.let(PhoneNormalizer::display).orEmpty()
        phoneStateText.text = when {
            current == null -> "Текущ телефон: зарежда се…"
            current.phoneVerified -> "Текущ телефон: ${displayPhone.ifBlank { "няма" }} · потвърден"
            else -> "Текущ телефон: ${displayPhone.ifBlank { "няма" }} · непотвърден"
        }
        renderActions()
    }

    private fun renderActions() {
        val current = profile
        val enteredName = nameInput.text?.toString().orEmpty().trim()
        val enteredEmail = emailInput.text?.toString().orEmpty().trim()
        val enteredPhone = phoneInput.text?.toString().orEmpty().trim()
        val nameChanged = current != null && enteredName.isNotBlank() && enteredName != current.userName
        val emailChanged = current != null && enteredEmail.isNotBlank() && enteredEmail != current.userEmail
        val phoneChanged = current != null &&
            enteredPhone.isNotBlank() &&
            !PhoneNormalizer.samePhone(enteredPhone, current.userPhone)

        nameButton.text = if (nameChanged) "Запази името" else "Името не е променено"
        emailButton.text = if (emailChanged) "Потвърди новия имейл" else "Въведи различен имейл"
        phoneButton.text = if (phoneChanged) "Потвърди новия телефон" else "Въведи различен телефон"
        nameButton.isEnabled = !loading && nameChanged
        emailButton.isEnabled = !loading && emailChanged
        phoneButton.isEnabled = !loading && phoneChanged
    }

    private fun saveName() {
        val value = nameInput.text?.toString().orEmpty().trim()
        if (value.isBlank()) return
        setStatus("")
        showLoading(true)
        executor.execute {
            val result = ProfileNameApi.update(applicationContext, value)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { user ->
                    CompanyAccountApi.applyProfileUser(applicationContext, user)
                    profile = CompanySessionStore.load(this)
                    renderProfile(overwriteInputs = true)
                    setStatus("Името е променено.", success = true)
                }.onFailure { error ->
                    setStatus(error.message ?: "Името не можа да бъде променено.")
                }
            }
        }
    }

    private fun requestReplacement(channel: String) {
        val rawValue = if (channel == "email") {
            emailInput.text?.toString().orEmpty().trim()
        } else {
            phoneInput.text?.toString().orEmpty().trim()
        }
        val value = if (channel == "email") {
            rawValue
        } else {
            PhoneNormalizer.normalize(rawValue).ifBlank { rawValue }
        }
        if (value.isBlank()) return
        setStatus("")
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.requestContactOtp(applicationContext, channel, value)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { challenge ->
                    setStatus("Кодът е изпратен до ${challenge.destinationHint}.", success = true)
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
        setStatus("")
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.verifyContactOtp(applicationContext, challenge.id, code)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { verification ->
                    if (verification.mergeRequired) {
                        if (verification.mergeToken.isBlank()) {
                            setStatus("Сървърът не върна валидно потвърждение за обединяване.")
                            return@onSuccess
                        }
                        ProfileMergeDialog.show(this, verification, channel) {
                            mergeProfiles(verification.mergeToken)
                        }
                        return@onSuccess
                    }
                    applyVerifiedContact(
                        verification.user,
                        if (channel == "email") {
                            "Имейлът е сменен и потвърден."
                        } else {
                            "Телефонът е сменен и потвърден."
                        },
                    )
                }.onFailure { error ->
                    setStatus(error.message ?: "Кодът не е приет. Старият контакт остава непроменен.")
                }
            }
        }
    }

    private fun mergeProfiles(mergeToken: String) {
        setStatus("")
        showLoading(true)
        executor.execute {
            val result = CompanyAccountApi.mergeProfiles(applicationContext, mergeToken)
            runOnUiThread {
                showLoading(false)
                result.onSuccess { user ->
                    applyVerifiedContact(
                        user,
                        "Профилите са обединени. Текущият вход остава активен.",
                    )
                }.onFailure { error ->
                    setStatus(error.message ?: "Профилите не можаха да бъдат обединени.")
                }
            }
        }
    }

    private fun applyVerifiedContact(user: CompanyAccountApi.ProfileUser, message: String) {
        CompanyAccountApi.applyProfileUser(applicationContext, user)
        profile = CompanySessionStore.load(this)
        renderProfile(overwriteInputs = true)
        setStatus(message, success = true)
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

    private fun logout() {
        showLoading(true)
        executor.execute {
            CompanyAccountApi.logout(applicationContext)
            CompanyAccountApi.clearSession(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showLoading(false)
                Toast.makeText(this, R.string.settings_registration_logged_out, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun showLoading(show: Boolean) {
        loading = show
        progress.visibility = if (show) View.VISIBLE else View.GONE
        nameInput.isEnabled = !show
        emailInput.isEnabled = !show
        phoneInput.isEnabled = !show
        logoutButton.isEnabled = !show
        renderActions()
    }

    private fun setStatus(value: String, success: Boolean = false) {
        val message = value.trim()
        if (message.isEmpty()) {
            statusText.text = ""
            statusText.background = null
            statusText.visibility = View.GONE
            return
        }

        val density = resources.displayMetrics.density
        fun dp(valueDp: Int) = (valueDp * density).toInt()
        val foreground = if (success) Color.rgb(22, 101, 52) else ContextCompat.getColor(this, R.color.calllog_error)
        val backgroundColor = if (success) Color.rgb(220, 252, 231) else Color.rgb(254, 226, 226)

        statusText.text = if (success) "✓ $message" else "⚠ $message"
        statusText.setTextColor(foreground)
        statusText.background = GradientDrawable().apply {
            setColor(backgroundColor)
            setStroke(dp(1), foreground)
            cornerRadius = dp(10).toFloat()
        }
        statusText.visibility = View.VISIBLE
        statusText.announceForAccessibility(statusText.text)
    }
}
