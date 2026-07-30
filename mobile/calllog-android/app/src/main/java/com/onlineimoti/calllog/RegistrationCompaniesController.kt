package com.onlineimoti.calllog

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.SettingsGroupRegistrationBinding

internal object RegistrationCompaniesController {
    fun renderLocked(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
        checking: Boolean,
    ) {
        binding.registrationConnectedCompaniesCard.alpha = if (checking) 0.78f else 0.62f
        binding.registrationCompaniesList.removeAllViews()
        binding.registrationCompaniesProgress.visibility = if (checking) View.VISIBLE else View.GONE
        binding.registrationCompaniesStatusText.apply {
            visibility = View.VISIBLE
            setText(
                if (checking) R.string.settings_registration_companies_loading
                else R.string.settings_registration_companies_require_profile,
            )
        }
        binding.registrationCreateCompanyButton.isEnabled = false
        binding.registrationJoinCompanyButton.isEnabled = false
        binding.registrationRefreshCompaniesButton.isEnabled = false
    }

    fun refresh(activity: AppCompatActivity, binding: SettingsGroupRegistrationBinding) {
        val config = ConfigStore.load(activity)
        if (!CallReportRemoteAccess.isReady(config)) {
            renderLocked(activity, binding, checking = false)
            return
        }

        binding.registrationConnectedCompaniesCard.alpha = 1f
        binding.registrationCompaniesList.removeAllViews()
        binding.registrationCompaniesProgress.visibility = View.VISIBLE
        binding.registrationCompaniesStatusText.apply {
            visibility = View.VISIBLE
            setText(R.string.settings_registration_companies_loading)
        }
        binding.registrationCreateCompanyButton.isEnabled = true
        binding.registrationJoinCompanyButton.isEnabled = true
        binding.registrationRefreshCompaniesButton.isEnabled = false

        Thread {
            val online = runCatching { CallReportTopicCompaniesRepository.refresh(activity.applicationContext, config) }
            val result = online.getOrNull()
                ?: runCatching { CallReportTopicCompaniesRepository.load(activity.applicationContext, config) }.getOrNull()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (result != null) renderCompanies(activity, binding, result.companies)
                else {
                    binding.registrationCompaniesProgress.visibility = View.GONE
                    binding.registrationRefreshCompaniesButton.isEnabled = true
                    binding.registrationCompaniesStatusText.apply {
                        visibility = View.VISIBLE
                        text = activity.getString(
                            R.string.settings_registration_company_load_failed,
                            online.exceptionOrNull()?.message.orEmpty(),
                        )
                    }
                }
            }
        }.start()
    }

    private fun renderCompanies(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
        companies: List<CallReportTopicCompany>,
    ) {
        binding.registrationConnectedCompaniesCard.alpha = 1f
        binding.registrationCompaniesProgress.visibility = View.GONE
        binding.registrationCreateCompanyButton.isEnabled = true
        binding.registrationJoinCompanyButton.isEnabled = true
        binding.registrationRefreshCompaniesButton.isEnabled = true
        binding.registrationCompaniesList.removeAllViews()
        if (companies.isEmpty()) {
            binding.registrationCompaniesStatusText.apply {
                visibility = View.VISIBLE
                setText(R.string.settings_registration_no_company_description)
            }
            return
        }
        binding.registrationCompaniesStatusText.visibility = View.GONE
        companies.forEachIndexed { index, company ->
            binding.registrationCompaniesList.addView(
                companyRow(activity, binding, company),
                verticalParams(activity, if (index == 0) 8 else 6),
            )
        }
    }

    private fun companyRow(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
        company: CallReportTopicCompany,
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(activity, R.color.calllog_bg))
                cornerRadius = dp(activity, 12).toFloat()
                setStroke(dp(activity, 1), ContextCompat.getColor(activity, R.color.calllog_border))
            }
        }
        row.addView(TextView(activity).apply {
            text = company.name
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.calllog_text))
            setTypeface(typeface, Typeface.BOLD)
        })
        row.addView(TextView(activity).apply {
            text = roleLabel(activity, company.role)
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.calllog_muted_text))
            setPadding(0, dp(activity, 3), 0, 0)
        })

        if (company.canDelete) {
            val ownerActions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            ownerActions.addView(MaterialButton(activity).apply {
                setText(R.string.settings_registration_company_information)
                isAllCaps = false
                setOnClickListener { showCompanyInfo(activity, company) }
            }, actionParams(activity))
            ownerActions.addView(MaterialButton(activity).apply {
                setText(R.string.settings_registration_company_delete)
                isAllCaps = false
                setOnClickListener { confirmDelete(activity, binding, company) }
            }, actionParams(activity, 6))
            row.addView(ownerActions, verticalParams(activity, 8))
        }

        if (company.canManageUsers) {
            val userActions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            userActions.addView(MaterialButton(activity).apply {
                text = "Покани колега"
                isAllCaps = false
                setOnClickListener { RegistrationActions.showInviteDialog(activity, company) }
            }, actionParams(activity))
            userActions.addView(MaterialButton(activity).apply {
                setText(R.string.settings_registration_users)
                isAllCaps = false
                setOnClickListener { RegistrationCompanyUsersUi.show(activity, company) }
            }, actionParams(activity, 6))
            row.addView(userActions, verticalParams(activity, 8))
        }
        return row
    }

    private fun showCompanyInfo(activity: AppCompatActivity, company: CallReportTopicCompany) {
        AlertDialog.Builder(activity)
            .setTitle(company.name)
            .setMessage(
                activity.getString(
                    R.string.settings_registration_company_details,
                    company.name,
                    company.id,
                    company.eik.ifBlank { "—" },
                    roleLabel(activity, company.role),
                ),
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDelete(
        activity: AppCompatActivity,
        binding: SettingsGroupRegistrationBinding,
        company: CallReportTopicCompany,
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_registration_company_delete_title)
            .setMessage(activity.getString(R.string.settings_registration_company_delete_message, company.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_registration_company_delete) { _, _ ->
                binding.registrationCreateCompanyButton.isEnabled = false
                binding.registrationJoinCompanyButton.isEnabled = false
                binding.registrationRefreshCompaniesButton.isEnabled = false
                binding.registrationCompaniesProgress.visibility = View.VISIBLE
                Thread {
                    val result = runCatching {
                        val newToken = CompanyManagementApi.delete(ConfigStore.load(activity), company.id)
                        val current = ConfigStore.load(activity.applicationContext)
                        ConfigStore.save(
                            activity.applicationContext,
                            current.copy(remoteEnabled = true, accessToken = newToken),
                        )
                    }
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        result.onSuccess {
                            Toast.makeText(
                                activity,
                                R.string.settings_registration_company_deleted,
                                Toast.LENGTH_SHORT,
                            ).show()
                            RegistrationActions.renderCompanySection(activity, binding)
                        }.onFailure { error ->
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.settings_registration_action_failed, error.message.orEmpty()),
                                Toast.LENGTH_LONG,
                            ).show()
                            RegistrationActions.renderCompanySection(activity, binding)
                        }
                    }
                }.start()
            }
            .show()
    }

    private fun roleLabel(activity: AppCompatActivity, role: String): String = activity.getString(
        when (role.lowercase()) {
            "owner" -> R.string.settings_registration_role_owner
            "admin" -> R.string.settings_registration_role_admin
            "member" -> R.string.settings_registration_role_member
            else -> R.string.settings_registration_role_broker
        },
    )

    private fun verticalParams(activity: AppCompatActivity, top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(activity, top) }

    private fun actionParams(activity: AppCompatActivity, start: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(activity, start)
        }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
