package com.onlineimoti.calllog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

internal object RegistrationCompanyUsersUi {
    fun show(activity: AppCompatActivity, company: CallReportTopicCompany) {
        val loading = AlertDialog.Builder(activity)
            .setTitle(company.name)
            .setMessage(R.string.settings_registration_users_loading)
            .setCancelable(false)
            .create()
        loading.show()
        val config = ConfigStore.load(activity)
        Thread {
            val result = runCatching { CompanyUsersApi.list(config, company) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                loading.dismiss()
                result.onSuccess { showUsersSnapshot(activity, it) }
                    .onFailure {
                        AlertDialog.Builder(activity)
                            .setTitle(company.name)
                            .setMessage(activity.getString(R.string.settings_registration_users_failed, it.message.orEmpty()))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
            }
        }.start()
    }

    private fun showUsersSnapshot(activity: AppCompatActivity, snapshot: CompanyUsersSnapshot) {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 6), dp(activity, 16), dp(activity, 12))
        }
        if (snapshot.users.isEmpty()) {
            list.addView(TextView(activity).apply {
                setText(R.string.settings_registration_users_empty)
                setTextColor(ContextCompat.getColor(activity, R.color.calllog_muted_text))
                textSize = 14f
            })
        } else {
            snapshot.users.forEachIndexed { index, user ->
                list.addView(userRow(activity, snapshot.company, user), verticalParams(activity, if (index == 0) 4 else 8))
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(snapshot.company.name)
            .setView(ScrollView(activity).apply { addView(list) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun userRow(activity: AppCompatActivity, company: CallReportTopicCompany, user: CompanyManagedUser): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 11), dp(activity, 10), dp(activity, 11), dp(activity, 10))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(activity, R.color.calllog_surface))
                cornerRadius = dp(activity, 11).toFloat()
                setStroke(dp(activity, 1), ContextCompat.getColor(activity, R.color.calllog_border))
            }
        }
        row.addView(TextView(activity).apply {
            text = user.name
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(activity, R.color.calllog_text))
        })
        row.addView(TextView(activity).apply {
            text = buildList {
                if (user.email.isNotBlank()) add(user.email)
                add(roleLabel(activity, user.role))
                if (!user.active) add(activity.getString(R.string.settings_registration_user_inactive))
                if (user.isCurrentUser) add(activity.getString(R.string.settings_registration_user_current))
            }.joinToString(" · ")
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.calllog_muted_text))
            setPadding(0, dp(activity, 3), 0, 0)
        })
        if (user.canDeactivate || user.canGenerateKey) {
            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            if (user.canGenerateKey) actions.addView(MaterialButton(activity).apply {
                setText(R.string.settings_registration_generate_key)
                isAllCaps = false
                setOnClickListener { generateKey(activity, company, user) }
            }, actionParams(activity))
            if (user.canDeactivate) actions.addView(MaterialButton(activity).apply {
                setText(R.string.settings_registration_deactivate_user)
                isAllCaps = false
                setOnClickListener { confirmDeactivate(activity, company, user) }
            }, actionParams(activity, 6))
            row.addView(actions, verticalParams(activity, 7))
        }
        return row
    }

    private fun confirmDeactivate(activity: AppCompatActivity, company: CallReportTopicCompany, user: CompanyManagedUser) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_registration_deactivate_title)
            .setMessage(activity.getString(R.string.settings_registration_deactivate_message, user.name, company.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_registration_deactivate_user) { _, _ ->
                runUserAction(activity, company) { config ->
                    CompanyUsersApi.deactivate(config, company.id, user.id)
                    null
                }
            }
            .show()
    }

    private fun generateKey(activity: AppCompatActivity, company: CallReportTopicCompany, user: CompanyManagedUser) {
        runUserAction(activity, company) { config -> CompanyUsersApi.generateKey(config, company.id, user.id) }
    }

    private fun runUserAction(
        activity: AppCompatActivity,
        company: CallReportTopicCompany,
        action: (AppConfig) -> GeneratedCompanyAccessKey?,
    ) {
        val loading = AlertDialog.Builder(activity)
            .setTitle(company.name)
            .setMessage(R.string.settings_registration_users_loading)
            .setCancelable(false)
            .create()
        loading.show()
        val config = ConfigStore.load(activity)
        Thread {
            val result = runCatching { action(config) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                loading.dismiss()
                result.onSuccess { generated ->
                    if (generated != null) showGeneratedKey(activity, generated) else show(activity, company)
                }.onFailure {
                    AlertDialog.Builder(activity)
                        .setTitle(company.name)
                        .setMessage(activity.getString(R.string.settings_registration_action_failed, it.message.orEmpty()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    private fun showGeneratedKey(activity: AppCompatActivity, generated: GeneratedCompanyAccessKey) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.settings_registration_key_ready) + " · " + generated.user.name)
            .setMessage(activity.getString(R.string.settings_registration_key_once) + "\n\n" + generated.key)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_registration_copy_key) { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Relationship Manager access key", generated.key))
                Toast.makeText(activity, R.string.settings_registration_key_copied, Toast.LENGTH_SHORT).show()
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
