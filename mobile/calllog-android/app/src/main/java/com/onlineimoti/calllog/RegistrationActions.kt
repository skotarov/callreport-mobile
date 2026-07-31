package com.onlineimoti.calllog

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.SettingsGroupRegistrationBinding

/** Shared profile, company and invitation actions reachable from Settings. */
internal object RegistrationActions {
    fun openProfileEditor(activity: AppCompatActivity) {
        val config = ConfigStore.load(activity)
        val target = if (config.accessToken.isNotBlank()) {
            ProfileEditorActivity::class.java
        } else {
            CompanyAccountActivity::class.java
        }
        activity.startActivity(Intent(activity, target).apply {
            if (target == CompanyAccountActivity::class.java) {
                putExtra(CompanyAccountActivity.EXTRA_MODE, CompanyAccountActivity.MODE_LOGIN)
            }
        })
    }

    fun openCreateCompany(activity: AppCompatActivity) {
        Thread {
            val result = CompanyAccountApi.refreshProfile(activity.applicationContext)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(activity.applicationContext, session)
                    val target = if (CompanyLicenseStore.loadValid(activity) != null) {
                        Intent(activity, CompanyAccountActivity::class.java)
                            .putExtra(CompanyAccountActivity.EXTRA_MODE, CompanyAccountActivity.MODE_CREATE_COMPANY)
                    } else {
                        Intent(activity, CompanyLicenseActivity::class.java)
                    }
                    activity.startActivity(target)
                }.onFailure {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.settings_registration_companies_require_profile),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    fun renderCompanySection(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
    ) {
        RegistrationCompaniesController.renderLocked(activity, binding, checking = true)
        RegistrationProfileController.refresh(activity, binding) { valid ->
            if (valid) RegistrationCompaniesController.refresh(activity, binding)
            else RegistrationCompaniesController.renderLocked(activity, binding, checking = false)
        }
    }

    fun showJoinDialog(activity: AppCompatActivity) {
        InvitationCenterDialogs.showIncoming(activity)
    }

    fun showInviteDialog(activity: AppCompatActivity, company: CallReportTopicCompany) {
        InvitationCenterDialogs.showOutgoing(activity, company)
    }
}
