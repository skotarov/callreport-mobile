package com.onlineimoti.calllog

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        val profileName = userName.trim()
        val email = userEmail.ifBlank { activity.getString(R.string.settings_registration_missing_email) }
        val phone = PhoneNormalizer.display(userPhone)
            .ifBlank { activity.getString(R.string.settings_registration_missing_phone) }
        val emailStatus = verificationStatus(emailVerified)
        val phoneStatus = verificationStatus(phoneVerified)
        binding.registrationCurrentProfileText.apply {
            visibility = View.VISIBLE
            text = profileSummary(
                activity = activity,
                profileName = profileName,
                email = email,
                emailStatus = emailStatus,
                emailVerified = emailVerified,
                phone = phone,
                phoneStatus = phoneStatus,
                phoneVerified = phoneVerified,
            )
            alpha = if (confirmedForCurrentToken) 1f else 0.82f
        }
        binding.registrationEditProfileButton.apply {
            visibility = View.VISIBLE
            isEnabled = true
            setText(R.string.settings_registration_edit_profile)
            setOnClickListener { RegistrationActions.openProfileEditor(activity) }
        }
    }

    private fun verificationStatus(verified: Boolean): String = if (verified) "✓" else "✕"

    private fun profileSummary(
        activity: AppCompatActivity,
        profileName: String,
        email: String,
        emailStatus: String,
        emailVerified: Boolean,
        phone: String,
        phoneStatus: String,
        phoneVerified: Boolean,
    ): CharSequence {
        val text = activity.getString(
            R.string.settings_registration_current_profile_details,
            profileName,
            email,
            emailStatus,
            phone,
            phoneStatus,
        )
        val styled = SpannableStringBuilder(text)
        val plain = styled.toString()

        var lineStart = 0
        while (lineStart < plain.length) {
            val lineEnd = plain.indexOf('\n', lineStart).let { if (it < 0) plain.length else it }
            val colon = plain.indexOf(':', lineStart)
            if (colon in lineStart until lineEnd) {
                styled.setSpan(
                    StyleSpan(Typeface.BOLD),
                    lineStart,
                    colon + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            lineStart = lineEnd + 1
        }

        var statusSearchFrom = 0
        statusSearchFrom = applyStatusStyle(
            styled = styled,
            status = emailStatus,
            verified = emailVerified,
            activity = activity,
            searchFrom = statusSearchFrom,
        )
        applyStatusStyle(
            styled = styled,
            status = phoneStatus,
            verified = phoneVerified,
            activity = activity,
            searchFrom = statusSearchFrom,
        )
        return styled
    }

    private fun applyStatusStyle(
        styled: SpannableStringBuilder,
        status: String,
        verified: Boolean,
        activity: AppCompatActivity,
        searchFrom: Int,
    ): Int {
        val start = styled.toString().indexOf(status, searchFrom)
        if (start < 0) return searchFrom
        val end = start + status.length
        val color = if (verified) {
            Color.rgb(21, 128, 61)
        } else {
            ContextCompat.getColor(activity, R.color.calllog_error)
        }
        styled.setSpan(
            ForegroundColorSpan(color),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        styled.setSpan(
            StyleSpan(Typeface.BOLD),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return end
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
