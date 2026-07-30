package com.onlineimoti.calllog

import android.graphics.Typeface
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

/** OTP modal that asks the server for the active challenge and counts down its server expiry. */
internal object ProfileOtpDialog {
    fun show(
        activity: AppCompatActivity,
        title: String,
        request: (((Result<CompanyAccountApi.OtpChallenge>) -> Unit) -> Unit),
        verify: (
            CompanyAccountApi.OtpChallenge,
            String,
            (Result<CompanyAccountApi.Session>) -> Unit,
        ) -> Unit,
        onVerified: (CompanyAccountApi.Session) -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val destinationText = TextView(activity).apply {
            textSize = 15f
            text = "Проверка за активен код…"
        }
        val countdownText = TextView(activity).apply {
            textSize = 22f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(12))
            minHeight = dp(52)
            text = "Оставащо време: --:--"
            visibility = View.VISIBLE
        }
        val codeInput = EditText(activity).apply {
            hint = "Шестцифрен код"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            setSingleLine(true)
            isEnabled = false
        }
        val errorText = TextView(activity).apply {
            textSize = 14f
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val progress = ProgressBar(activity)
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

        var challenge: CompanyAccountApi.OtpChallenge? = null
        var deadlineMs = 0L
        var timer: CountDownTimer? = null
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(content)
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Потвърди", null)
            .create()

        dialog.setOnShowListener {
            val confirmButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            confirmButton.isEnabled = false

            fun remainingMs(): Long =
                ProfileOtpTimer.remainingMs(deadlineMs, System.currentTimeMillis())

            fun renderCountdown() {
                val remaining = remainingMs()
                countdownText.visibility = View.VISIBLE
                countdownText.text = if (deadlineMs > 0L) {
                    "Оставащо време: ${ProfileOtpTimer.format(remaining)}"
                } else {
                    "Оставащо време: --:--"
                }
                if (deadlineMs > 0L && remaining == 0L) {
                    codeInput.isEnabled = false
                    confirmButton.isEnabled = false
                    errorText.apply {
                        text = "Кодът изтече. Затвори прозореца и поискай нов код."
                        visibility = View.VISIBLE
                    }
                }
            }

            fun beginCountdown(newDeadlineMs: Long) {
                timer?.cancel()
                deadlineMs = newDeadlineMs
                renderCountdown()
                val initialRemaining = remainingMs()
                if (deadlineMs > 0L && initialRemaining > 0L) {
                    timer = object : CountDownTimer(initialRemaining, 250L) {
                        override fun onTick(millisUntilFinished: Long) = renderCountdown()
                        override fun onFinish() = renderCountdown()
                    }.start()
                }
            }

            fun startCountdown(received: CompanyAccountApi.OtpChallenge) {
                val serverDeadline = ProfileOtpTimer.deadline(
                    expiresAtMs = received.expiresAtMs,
                    openedAtMs = System.currentTimeMillis(),
                )
                challenge = received
                destinationText.text = "Активният код е за ${received.destinationHint}."
                if (received.debugCode.isNotBlank()) codeInput.setText(received.debugCode)
                progress.visibility = View.GONE
                beginCountdown(serverDeadline)
                val active = serverDeadline > System.currentTimeMillis()
                codeInput.isEnabled = active
                confirmButton.isEnabled = active
                if (!active) {
                    errorText.apply {
                        text = "Сървърът не върна активен срок за кода."
                        visibility = View.VISIBLE
                    }
                }
                codeInput.clearFocus()
            }

            fun showRequestError(error: Throwable) {
                timer?.cancel()
                deadlineMs = 0L
                progress.visibility = View.GONE
                destinationText.text = "Кодът не можа да бъде заявен."
                renderCountdown()
                codeInput.isEnabled = false
                confirmButton.isEnabled = false
                cancelButton.isEnabled = true
                errorText.apply {
                    text = error.message ?: "Неуспешна заявка към сървъра."
                    visibility = View.VISIBLE
                }
            }

            fun setVerifying(verifying: Boolean) {
                val active = challenge != null && remainingMs() > 0L
                progress.visibility = if (verifying) View.VISIBLE else View.GONE
                codeInput.isEnabled = !verifying && active
                confirmButton.isEnabled = !verifying && active
                cancelButton.isEnabled = !verifying
            }

            confirmButton.setOnClickListener {
                val activeChallenge = challenge ?: return@setOnClickListener
                val code = codeInput.text?.toString().orEmpty().trim()
                if (!code.matches(Regex("\\d{6}"))) {
                    codeInput.error = "Въведи шестцифрения код"
                    return@setOnClickListener
                }
                errorText.visibility = View.GONE
                setVerifying(true)
                verify(activeChallenge, code) { result ->
                    activity.runOnUiThread {
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

            request { result ->
                activity.runOnUiThread {
                    if (dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                        result.onSuccess(::startCountdown).onFailure(::showRequestError)
                    }
                }
            }
        }
        dialog.setOnDismissListener { timer?.cancel() }
        dialog.show()
    }
}
