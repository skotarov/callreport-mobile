package com.onlineimoti.calllog

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.SettingsGroupRegistrationBinding

/**
 * Shows the locally remembered profile immediately, but enables company actions
 * only after the server confirms the current access token.
 */
internal object RegistrationProfileController {
    fun refresh(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
        onValidated: (Boolean) -> Unit,
    ) {
        val remembered = CompanySessionStore.load(activity)
        if (remembered != null) {
            renderSnapshot(activity, binding, remembered, confirmedForCurrentToken = false)
        } else {
            renderSignedOut(activity, binding)
        }

        val config = ConfigStore.load(activity)
        if (config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            onValidated(false)
            return
        }

        val expectedToken = config.accessToken
        if (remembered == null) renderLoading(activity, binding)

        Thread {
            val result = CompanyAccountApi.refreshProfile(activity.applicationContext)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (ConfigStore.load(activity).accessToken != expectedToken) {
                    onValidated(false)
                    return@runOnUiThread
                }

                result.onSuccess { session ->
                    CompanyAccountApi.applySession(activity.applicationContext, session)
                    renderSession(activity, binding, session)
                    onValidated(true)
                }.onFailure { error ->
                    renderError(activity, binding, error)
                    onValidated(false)
                }
            }
        }.start()
    }

    private fun renderSession(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
        session: CompanyAccountApi.Session,
    ) {
        renderProfile(
            activity = activity,
            binding = binding,
            userName = session.userName,
            userEmail = session.userEmail,
            userPhone = session.userPhone,
            emailVerified = session.emailVerified,
            phoneVerified = session.phoneVerified,
            confirmedForCurrentToken = true,
        )
    }

    private fun renderSnapshot(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
        snapshot: CompanySessionStore.Snapshot,
        confirmedForCurrentToken: Boolean,
    ) {
        renderProfile(
            activity = activity,
            binding = binding,
            userName = snapshot.userName,
            userEmail = snapshot.userEmail,
            userPhone = snapshot.userPhone,
            emailVerified = snapshot.emailVerified,
            phoneVerified = snapshot.phoneVerified,
            confirmedForCurrentToken = confirmedForCurrentToken,
        )
    }

    private fun renderProfile(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
        userName: String,
        userEmail: String,
        userPhone: String,
        emailVerified: Boolean,
        phoneVerified: Boolean,
        confirmedForCurrentToken: Boolean,
    ) {
        val profileName = userName.ifBlank { activity.getString(R.string.settings_registration_profile_license) }
        val email = userEmail.ifBlank { activity.getString(R.string.settings_registration_missing_email) }
        val phone = userPhone.ifBlank { activity.getString(R.string.settings_registration_missing_phone) }
        val emailStatus = activity.getString(
            if (emailVerified) R.string.settings_registration_contact_verified
            else R.string.settings_registration_contact_unverified,
        )
        val phoneStatus = activity.getString(
            if (phoneVerified) R.string.settings_registration_contact_verified
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
            alpha = if (confirmedForCurrentToken) 1f else 0.82f
        }
        binding.registrationEditProfileButton.apply {
            visibility = View.VISIBLE
            isEnabled = true
            setText(R.string.settings_registration_profile_license)
            setOnClickListener { RegistrationActions.openProfileEditor(activity) }
        }
    }

    private fun renderLoading(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
    ) {
        binding.registrationCurrentProfileText.apply {
            visibility = View.VISIBLE
            text = activity.getString(R.string.settings_registration_profile_loading)
            alpha = 1f
        }
        configureAccessButton(activity, binding)
    }

    private fun renderSignedOut(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
    ) {
        binding.registrationCurrentProfileText.apply {
            visibility = View.VISIBLE
            text = activity.getString(R.string.settings_registration_no_active_profile)
            alpha = 1f
        }
        configureAccessButton(activity, binding)
    }

    private fun renderError(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
        error: Throwable,
    ) {
        val detail = error.message.orEmpty().ifBlank {
            activity.getString(R.string.settings_registration_profile_server_error)
        }
        binding.registrationCurrentProfileText.apply {
            visibility = View.VISIBLE
            text = activity.getString(R.string.settings_registration_profile_load_failed, detail)
            alpha = 1f
        }
        configureAccessButton(activity, binding)
    }

    private fun configureAccessButton(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
    ) {
        binding.registrationEditProfileButton.apply {
            visibility = View.VISIBLE
            isEnabled = true
            setText(R.string.settings_registration_login_or_create_profile)
            setOnClickListener {
                activity.startActivity(
                    Intent(activity, CompanyAccountActivity::class.java)
                        .putExtra(CompanyAccountActivity.EXTRA_MODE, CompanyAccountActivity.MODE_LOGIN),
                )
            }
        }
    }
}
