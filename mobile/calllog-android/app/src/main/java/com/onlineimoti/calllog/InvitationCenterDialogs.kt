package com.onlineimoti.calllog

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date

/** Dialogs for received invitations and company pending invitations. */
internal object InvitationCenterDialogs {
    fun showIncoming(activity: AppCompatActivity) {
        if (CompanySessionStore.load(activity) == null) {
            AlertDialog.Builder(activity)
                .setTitle("Присъедини се по покана")
                .setMessage("Първо влез или създай профил и потвърди телефон или имейл.")
                .setPositiveButton("Профил") { _, _ -> RegistrationActions.openProfileEditor(activity) }
                .setNegativeButton("Отказ", null)
                .show()
            return
        }
        val loading = loadingDialog(activity, "Проверявам поканите към твоя потвърден телефон и имейл…")
        Thread {
            val result = InvitationCenterApi.listReceived(activity.applicationContext)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                loading.dismiss()
                result.onSuccess { showIncomingList(activity, it) }
                    .onFailure { showError(activity, "Поканите не можаха да бъдат заредени.", it) }
            }
        }.start()
    }

    fun showOutgoing(activity: AppCompatActivity, company: CallReportTopicCompany) {
        if (!company.canManageUsers) {
            AlertDialog.Builder(activity)
                .setTitle(company.name)
                .setMessage("Само собственик или администратор може да кани служители.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val loading = loadingDialog(activity, "Зареждам неприетите покани…")
        Thread {
            val result = InvitationCenterApi.listSent(activity.applicationContext, company.id)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                loading.dismiss()
                result.onSuccess { showOutgoingList(activity, company, it) }
                    .onFailure { showError(activity, "Поканите не можаха да бъдат заредени.", it) }
            }
        }.start()
    }

    private fun showIncomingList(
        activity: AppCompatActivity,
        invitations: List<InvitationCenterApi.Invitation>,
    ) {
        val content = column(activity)
        content.addView(TextView(activity).apply {
            text = "Показват се само покани към потвърден телефон или имейл на текущия профил."
            textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.calllog_muted_text))
        })
        if (invitations.isEmpty()) {
            content.addView(TextView(activity).apply {
                text = "Няма чакащи покани."
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, dp(activity, 24), 0, dp(activity, 24))
            })
        } else {
            invitations.forEach { invitation ->
                content.addView(invitationRow(activity, invitation, incoming = true) { button ->
                    button.isEnabled = false
                    Thread {
                        val result = InvitationCenterApi.accept(activity.applicationContext, invitation.id)
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            result.onSuccess { session ->
                                Toast.makeText(
                                    activity,
                                    "Успешно присъединяване към ${session.organizationName.ifBlank { "фирмата" }}. Токънът е сменен автоматично.",
                                    Toast.LENGTH_LONG,
                                ).show()
                                activity.recreate()
                            }.onFailure { error ->
                                button.isEnabled = true
                                showError(activity, "Поканата не можа да бъде приета.", error)
                            }
                        }
                    }.start()
                }, params(activity, 10))
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("Покани към мен")
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("Затвори", null)
            .show()
    }

    private fun showOutgoingList(
        activity: AppCompatActivity,
        company: CallReportTopicCompany,
        invitations: List<InvitationCenterApi.Invitation>,
    ) {
        val content = column(activity)
        content.addView(TextView(activity).apply {
            text = "Попълни само едното поле. Поканата ще се появи при потребителя, след като същият телефон или имейл е потвърден в неговия профил."
            textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.calllog_muted_text))
        })
        val phone = EditText(activity).apply {
            hint = "Телефон на колегата"
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
        }
        val email = EditText(activity).apply {
            hint = "Имейл на колегата"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        val validation = TextView(activity).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.calllog_error))
            visibility = View.GONE
        }
        content.addView(phone, params(activity, 12))
        content.addView(email, params(activity, 6))
        content.addView(validation, params(activity, 4))

        val createButton = MaterialButton(activity).apply {
            text = "Изпрати покана"
            isAllCaps = false
        }
        content.addView(createButton, params(activity, 8))

        content.addView(TextView(activity).apply {
            text = "Неприети покани"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(activity, 18), 0, 0)
        })
        if (invitations.isEmpty()) {
            content.addView(TextView(activity).apply {
                text = "Няма чакащи покани."
                textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.calllog_muted_text))
                setPadding(0, dp(activity, 10), 0, dp(activity, 8))
            })
        } else {
            invitations.forEach { invitation ->
                content.addView(invitationRow(activity, invitation, incoming = false) { button ->
                    button.isEnabled = false
                    Thread {
                        val result = InvitationCenterApi.cancel(
                            activity.applicationContext,
                            company.id,
                            invitation.id,
                        )
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            result.onSuccess {
                                Toast.makeText(activity, "Поканата е отказана.", Toast.LENGTH_SHORT).show()
                                showOutgoing(activity, company)
                            }.onFailure { error ->
                                button.isEnabled = true
                                showError(activity, "Поканата не можа да бъде отказана.", error)
                            }
                        }
                    }.start()
                }, params(activity, 8))
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Покани в ${company.name}")
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("Затвори", null)
            .create()
        createButton.setOnClickListener {
            val rawPhone = phone.text?.toString().orEmpty().trim()
            val rawEmail = email.text?.toString().orEmpty().trim()
            if ((rawPhone.isBlank() && rawEmail.isBlank()) || (rawPhone.isNotBlank() && rawEmail.isNotBlank())) {
                validation.text = "Попълни само телефон или само имейл."
                validation.visibility = View.VISIBLE
                return@setOnClickListener
            }
            validation.visibility = View.GONE
            createButton.isEnabled = false
            Thread {
                val result = InvitationCenterApi.create(
                    activity.applicationContext,
                    company.id,
                    rawEmail,
                    rawPhone,
                )
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    result.onSuccess {
                        dialog.dismiss()
                        Toast.makeText(activity, "Поканата е създадена.", Toast.LENGTH_SHORT).show()
                        showOutgoing(activity, company)
                    }.onFailure { error ->
                        createButton.isEnabled = true
                        validation.text = error.message ?: "Поканата не можа да бъде създадена."
                        validation.visibility = View.VISIBLE
                    }
                }
            }.start()
        }
        dialog.show()
    }

    private fun invitationRow(
        activity: AppCompatActivity,
        invitation: InvitationCenterApi.Invitation,
        incoming: Boolean,
        onAction: (MaterialButton) -> Unit,
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(activity, R.color.calllog_surface))
                setStroke(dp(activity, 1), ContextCompat.getColor(activity, R.color.calllog_border))
                cornerRadius = dp(activity, 12).toFloat()
            }
        }
        row.addView(TextView(activity).apply {
            text = invitation.organizationName.ifBlank { invitation.organizationId }
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(activity, R.color.calllog_text))
        })
        val target = if (invitation.targetChannel == "sms") {
            PhoneNormalizer.display(invitation.targetValue)
        } else invitation.targetValue
        row.addView(TextView(activity).apply {
            text = buildString {
                append(if (invitation.targetChannel == "sms") "Телефон: " else "Имейл: ")
                append(target)
                append("\nИзтича: ").append(formatDate(invitation.expiresAtMs))
            }
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.calllog_muted_text))
            setPadding(0, dp(activity, 4), 0, 0)
        })
        InvitationMembershipIndicator.add(activity, row, invitation)
        val action = MaterialButton(activity).apply {
            text = if (incoming) "Присъедини се" else "Откажи"
            isAllCaps = false
            if (!incoming) setTextColor(ContextCompat.getColor(activity, R.color.calllog_error))
            setOnClickListener { onAction(this) }
        }
        row.addView(action, params(activity, 8))
        return row
    }

    private fun loadingDialog(activity: AppCompatActivity, message: String): AlertDialog {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18))
            addView(ProgressBar(activity), LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36)))
            addView(TextView(activity).apply {
                text = message
                textSize = 15f
                setPadding(dp(activity, 14), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return AlertDialog.Builder(activity)
            .setTitle("Покани")
            .setView(content)
            .setCancelable(false)
            .create()
            .also(AlertDialog::show)
    }

    private fun showError(activity: AppCompatActivity, fallback: String, error: Throwable) {
        AlertDialog.Builder(activity)
            .setTitle("Покани")
            .setMessage(error.message?.takeIf(String::isNotBlank) ?: fallback)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun column(activity: AppCompatActivity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 16))
    }

    private fun params(activity: AppCompatActivity, top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(activity, top) }

    private fun formatDate(value: Long): String {
        if (value <= 0L) return "—"
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
