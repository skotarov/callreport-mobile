package com.onlineimoti.calllog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.SettingsGroupRegistrationBinding
import java.util.Collections

/** Shared profile and company-access actions, reachable from Profile and companies. */
internal object RegistrationActions {
    private val profileRefreshes = Collections.synchronizedSet(mutableSetOf<Int>())

    fun openCompanyAccount(activity: AppCompatActivity) {
        val target = if (ConfigStore.load(activity).accessToken.isNotBlank()) {
            ProfileEditorActivity::class.java
        } else {
            CompanyAccountActivity::class.java
        }
        activity.startActivity(Intent(activity, target))
    }

    fun renderCompanySection(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
    ) {
        val config = ConfigStore.load(activity)
        val profile = CompanySessionStore.load(activity)
        val hasAuthenticatedToken = config.accessToken.isNotBlank()

        binding.registrationCompanyAccountButton.text = if (hasAuthenticatedToken) {
            "Преглед и редакция на профила"
        } else {
            activity.getString(R.string.settings_registration_profile_license)
        }

        when {
            profile != null -> renderProfile(activity, binding, profile)
            hasAuthenticatedToken -> {
                binding.registrationCurrentProfileText.apply {
                    visibility = View.VISIBLE
                    text = "Зареждам данните на влезлия профил…"
                }
                binding.registrationLogoutButton.visibility = View.VISIBLE
                binding.registrationLogoutButton.isEnabled = true
                refreshProfileIfNeeded(activity, binding)
            }
            else -> {
                binding.registrationCurrentProfileText.visibility = View.GONE
                binding.registrationLogoutButton.visibility = View.GONE
            }
        }
        RegistrationCompaniesController.refresh(activity, binding)
    }

    private fun renderProfile(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
        profile: CompanySessionStore.Snapshot,
    ) {
        val profileName = profile.userName.ifBlank {
            activity.getString(R.string.settings_registration_profile_license)
        }
        val email = profile.userEmail.ifBlank {
            activity.getString(R.string.settings_registration_missing_email)
        }
        val phone = profile.userPhone.ifBlank {
            activity.getString(R.string.settings_registration_missing_phone)
        }
        val emailStatus = activity.getString(
            if (profile.emailVerified) R.string.settings_registration_contact_verified
            else R.string.settings_registration_contact_unverified,
        )
        val phoneStatus = activity.getString(
            if (profile.phoneVerified) R.string.settings_registration_contact_verified
            else R.string.settings_registration_contact_unverified,
        )
        binding.registrationCurrentProfileText.apply {
            visibility = View.VISIBLE
            text = activity.getString(
                R.string.settings_registration_current_profile_details,
                profileName,
                email,
                emailStatus,
                phone,
                phoneStatus,
            )
        }
        binding.registrationLogoutButton.visibility = View.VISIBLE
        binding.registrationLogoutButton.isEnabled = true
    }

    private fun refreshProfileIfNeeded(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
    ) {
        val refreshKey = System.identityHashCode(activity)
        if (!profileRefreshes.add(refreshKey)) return
        Thread {
            val result = CompanyAccountApi.refreshProfile(activity.applicationContext)
            activity.runOnUiThread {
                profileRefreshes.remove(refreshKey)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(activity.applicationContext, session)
                    CompanySessionStore.load(activity)?.let { renderProfile(activity, binding, it) }
                }.onFailure { error ->
                    binding.registrationCurrentProfileText.apply {
                        visibility = View.VISIBLE
                        text = "Профилът не можа да бъде зареден: ${error.message ?: "неизвестна грешка"}"
                    }
                }
            }
        }.start()
    }

    fun logout(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
    ) {
        binding.registrationLogoutButton.isEnabled = false
        Thread {
            CompanyAccountApi.logout(activity.applicationContext)
            CompanyAccountApi.clearSession(activity.applicationContext)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                Toast.makeText(activity, R.string.settings_registration_logged_out, Toast.LENGTH_SHORT).show()
                renderCompanySection(activity, binding)
            }
        }.start()
    }

    fun showJoinDialog(activity: AppCompatActivity) {
        if (CompanySessionStore.load(activity) == null) {
            AlertDialog.Builder(activity)
                .setTitle("Присъедини се по покана")
                .setMessage("Първо създай профил или влез с еднократен код по имейл или SMS. След това ще въведеш само кода от поканата.")
                .setPositiveButton("Профил") { _, _ -> openCompanyAccount(activity) }
                .setNegativeButton("Отказ", null)
                .show()
            return
        }

        val code = EditText(activity).apply {
            hint = "Код от поканата"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            val horizontal = (activity.resources.displayMetrics.density * 24).toInt()
            setPadding(horizontal, 0, horizontal, 0)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Присъедини се по покана")
            .setMessage("Поканата ще добави текущия профил към конкретната фирма.")
            .setView(code)
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Присъедини се", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val safeCode = code.text?.toString().orEmpty().trim()
                if (safeCode.isBlank()) {
                    dialog.setMessage("Въведи кода от поканата.")
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val result = InvitedAccountApi.accept(activity.applicationContext, safeCode)
                    activity.runOnUiThread {
                        result.onSuccess { session ->
                            CompanyAccountApi.applySession(activity.applicationContext, session)
                            dialog.dismiss()
                            Toast.makeText(
                                activity,
                                "Профилът е добавен към ${session.organizationName.ifBlank { "фирмата" }}. Издаден е нов ключ за връзка.",
                                Toast.LENGTH_LONG,
                            ).show()
                            activity.recreate()
                        }.onFailure { error ->
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.setMessage(error.message ?: "Неуспешно присъединяване към фирмата.")
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    fun showInviteDialog(activity: AppCompatActivity, company: CallReportTopicCompany) {
        if (!company.canManageUsers) {
            AlertDialog.Builder(activity)
                .setTitle(company.name)
                .setMessage("Само собственик или администратор може да кани служители в тази фирма.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val email = EditText(activity).apply {
            hint = "Имейл на колегата"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            val horizontal = (activity.resources.displayMetrics.density * 24).toInt()
            setPadding(horizontal, 0, horizontal, 0)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Покани в ${company.name}")
            .setMessage("Ще се създаде 7-дневен еднократен код за роля „Служител“, валиден само за този имейл.")
            .setView(email)
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Създай покана", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val safeEmail = email.text?.toString().orEmpty().trim()
                if (safeEmail.isBlank()) {
                    dialog.setMessage("Въведи имейл на колегата.")
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val result = CompanyInvitationApi.create(
                        activity.applicationContext,
                        company.id,
                        safeEmail,
                        "member",
                    )
                    activity.runOnUiThread {
                        result.onSuccess { invitation ->
                            dialog.dismiss()
                            showInvitationCode(activity, company, invitation, safeEmail)
                        }.onFailure { error ->
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.setMessage(error.message ?: "Неуспешно създаване на покана.")
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun showInvitationCode(
        activity: AppCompatActivity,
        company: CallReportTopicCompany,
        invitation: CompanyInvitationApi.CreatedInvitation,
        fallbackEmail: String,
    ) {
        val message = buildString {
            append("Фирма: ").append(company.name)
            append("\nИмейл: ").append(invitation.email.ifBlank { fallbackEmail })
            append("\n\n").append(invitation.code)
            append("\n\nКодът е валиден 7 дни и може да се използва само веднъж.")
        }
        AlertDialog.Builder(activity)
            .setTitle("Поканата е готова")
            .setMessage(message)
            .setNegativeButton("Готово", null)
            .setPositiveButton("Копирай поканата") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Relationship Manager invitation", invitation.code))
                Toast.makeText(activity, "Поканата е копирана.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
