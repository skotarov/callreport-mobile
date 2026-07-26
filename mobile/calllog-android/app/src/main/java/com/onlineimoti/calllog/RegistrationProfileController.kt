package com.onlineimoti.calllog

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.SettingsGroupRegistrationBinding
import java.util.Collections

/** Renders only the profile confirmed by the server for the current access token. */
internal object RegistrationProfileController {
    private val inFlight = Collections.synchronizedSet(mutableSetOf<SettingsGroupRegistrationBinding>())

    fun refresh(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
    ) {
        val config = ConfigStore.load(activity)
        if (config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            renderSignedOut(activity, binding)
            return
        }
        if (!inFlight.add(binding)) return

        val expectedToken = config.accessToken
        binding.registrationCurrentProfileText.apply {
            visibility = View.VISIBLE
            text = activity.getString(R.string.settings_registration_profile_loading)
        }
        binding.registrationLogoutButton.apply {
            visibility = View.VISIBLE
            isEnabled = true
        }

        Thread {
            val result = CompanyAccountApi.refreshProfile(activity.applicationContext)
            activity.runOnUiThread {
                inFlight.remove(binding)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

                val currentToken = ConfigStore.load(activity).accessToken
                if (currentToken != expectedToken) {
                    refresh(activity, binding)
                    return@runOnUiThread
                }

                result.onSuccess { session ->
                    CompanyAccountApi.applySession(activity.applicationContext, session)
                    renderSession(activity, binding, session)
                }.onFailure { error ->
                    binding.registrationCurrentProfileText.apply {
                        visibility = View.VISIBLE
                        text = activity.getString(
                            R.string.settings_registration_profile_load_failed,
                            error.message.orEmpty().ifBlank {
                                activity.getString(R.string.settings_registration_profile_server_error)
                            },
                        )
                    }
                    binding.registrationLogoutButton.apply {
                        visibility = View.VISIBLE
                        isEnabled = true
                    }
                }
            }
        }.start()
    }

    private fun renderSession(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
        session: CompanyAccountApi.Session,
    ) {
        val profileName = session.userName.ifBlank {
            activity.getString(R.string.settings_registration_profile_license)
        }
        val email = session.userEmail.ifBlank {
            activity.getString(R.string.settings_registration_missing_email)
        }
        val phone = session.userPhone.ifBlank {
            activity.getString(R.string.settings_registration_missing_phone)
        }
        val emailStatus = activity.getString(
            if (session.emailVerified) R.string.settings_registration_contact_verified
            else R.string.settings_registration_contact_unverified,
        )
        val phoneStatus = activity.getString(
            if (session.phoneVerified) R.string.settings_registration_contact_verified
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
        binding.registrationLogoutButton.apply {
            visibility = View.VISIBLE
            isEnabled = true
        }
    }

    private fun renderSignedOut(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
    ) {
        inFlight.remove(binding)
        binding.registrationCurrentProfileText.apply {
            visibility = View.VISIBLE
            text = activity.getString(R.string.settings_registration_no_active_profile)
        }
        binding.registrationLogoutButton.visibility = View.GONE
    }
}
