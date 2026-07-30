package com.onlineimoti.calllog

import android.os.CountDownTimer
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** OTP modal that keeps the code field open, verifies it and shows the server expiry countdown. */
internal object ProfileOtpDialog {
    fun show(
        activity: AppCompatActivity,
        challenge: CompanyAccountApi.OtpChallenge,
        title: String,
        verify: (String, (Result<CompanyAccountApi.Session>) -> Unit) -> Unit,
        onVerified: (CompanyAccountApi.Session) -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val openedAtMs = System.currentTimeMillis()
        val deadlineMs = ProfileOtpTimer.deadline(challenge.expiresAtMs, openedAtMs)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val destinationText = TextView(activity).apply {
            textSize = 15f
            text = "Кодът е изпратен до ${challenge.destinationHint}."
        }
        val countdownText = TextView(activity).apply {
            textSize = 16f
            setPadding(0, dp(10), 0, dp(10))
        }
        val codeInput = EditText(activity).apply {
            hint = "Шестцифрен код"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            setSingleLine(true)
            if (challenge.debugCode.isNotBlank()) setText(challenge.debugCode)
        }
        val errorText = TextView(activity).apply {
            textSize = 14f
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val progress = ProgressBar(activity).apply {
            visibility = View.GONE
        }
        content.addView(destinationText)
        content.addView(countdownText)
        content.addView(codeInput)
        content.addView(errorText)
        content.addView(
            progress,
            LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            },
        )

        var timer: CountDownTimer? = null
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(content)
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Потвърди", null)
            .create()

        dialog.setOnShowListener {
            val confirmButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            fun remainingMs(): Long =
                ProfileOtpTimer.remainingMs(deadlineMs, System.currentTimeMillis())

            fun renderCountdown() {
                val remaining = remainingMs()
                countdownText.text = "Кодът е валиден още ${ProfileOtpTimer.format(remaining)}"
                if (remaining == 0L) {
                    codeInput.isEnabled = false
                    confirmButton.isEnabled = false
                    errorText.apply {
                        text = "Кодът изтече. Затвори прозореца и изпрати нов код."
                        visibility = View.VISIBLE
                    }
                }
            }

            fun setVerifying(verifying: Boolean) {
                val active = remainingMs() > 0L
                progress.visibility = if (verifying) View.VISIBLE else View.GONE
                codeInput.isEnabled = !verifying && active
                confirmButton.isEnabled = !verifying && active
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !verifying
            }

            renderCountdown()
            val initialRemaining = remainingMs()
            if (initialRemaining > 0L) {
                timer = object : CountDownTimer(initialRemaining, 250L) {
                    override fun onTick(millisUntilFinished: Long) = renderCountdown()
                    override fun onFinish() = renderCountdown()
                }.start()
            }

            confirmButton.setOnClickListener {
                val code = codeInput.text?.toString().orEmpty().trim()
                if (!code.matches(Regex("\\d{6}"))) {
                    codeInput.error = "Въведи шестцифрения код"
                    return@setOnClickListener
                }
                errorText.visibility = View.GONE
                setVerifying(true)
                verify(code) { result ->
                    if (dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                        result.onSuccess { session ->
                            timer?.cancel()
                            dialog.dismiss()
                            onVerified(session)
                        }.onFailure { error ->
                            setVerifying(false)
                            errorText.apply {
                                text = error.message ?: "Кодът не е правилен или е изтекъл."
                                visibility = View.VISIBLE
                            }
                            if (remainingMs() > 0L) {
                                codeInput.requestFocus()
                                codeInput.selectAll()
                            }
                        }
                    }
                }
            }
        }
        dialog.setOnDismissListener { timer?.cancel() }
        dialog.show()
    }
}
