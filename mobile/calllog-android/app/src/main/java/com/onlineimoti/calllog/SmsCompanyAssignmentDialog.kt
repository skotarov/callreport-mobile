package com.onlineimoti.calllog

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Small editor that assigns one concrete SMS to exactly one company/case. */
internal class SmsCompanyAssignmentDialog(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> android.graphics.drawable.GradientDrawable,
) {
    fun show(
        phone: String,
        title: String,
        sms: SmsMessageRecord,
        initialCompanyId: String,
        onSaved: () -> Unit,
    ) {
        if (!CrmContactSyncStore.isEnabled(activity.applicationContext, phone)) {
            Toast.makeText(activity, "Само CRM маркирани контакти могат да се запишат към фирма.", Toast.LENGTH_SHORT).show()
            return
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(8))
            background = AppModalStyle.surface(activity)
        }
        root.addView(TextView(activity).apply {
            text = "SMS — фирма / случай"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 23, 42))
        })
        root.addView(TextView(activity).apply {
            text = listOf(
                title.ifBlank { phone },
                PhoneCallReader.formatStartedAt(sms.timestampMs),
                sms.directionLabel,
            ).filter { it.isNotBlank() }.joinToString(" • ")
            textSize = 12.5f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(TextView(activity).apply {
            text = sms.body
            textSize = 14.5f
            setTextColor(Color.rgb(30, 41, 59))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRect(Color.rgb(248, 250, 252), dp(10), Color.TRANSPARENT, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        })
        root.addView(TextView(activity).apply {
            text = "Повод:"
            textSize = 12.5f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, dp(14), 0, dp(4))
        })
        val spinner = Spinner(activity).apply {
            isEnabled = false
            background = roundedRect(Color.rgb(255, 255, 255), dp(10), Color.rgb(203, 213, 225), dp(1))
        }
        root.addView(spinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val status = TextView(activity).apply {
            text = "Зареждам фирмите…"
            textSize = 12f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(status)

        val dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Запази", null)
            .create()
        dialog.setOnShowListener {
            AppModalStyle.styleAlert(dialog)
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.isEnabled = false
            var companies: List<CallReportTopicCompany> = emptyList()
            Thread {
                val result = runCatching {
                    CallReportTopicCompaniesClient.fetch(ConfigStore.load(activity.applicationContext))
                }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@runOnUiThread
                    companies = result.getOrDefault(emptyList())
                    val labels = buildList {
                        add("Избери")
                        companies.forEach { add(it.name) }
                    }
                    spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
                    spinner.isEnabled = companies.isNotEmpty()
                    val initialIndex = companies.indexOfFirst { it.id == initialCompanyId }.takeIf { it >= 0 }?.plus(1) ?: 0
                    spinner.setSelection(initialIndex, false)
                    status.text = when {
                        result.isFailure -> "Не успях да заредя фирмите"
                        companies.isEmpty() -> "Няма достъпна фирма"
                        initialIndex > 0 -> "Това SMS е записано към избраната фирма"
                        else -> "Избери към коя фирма/случай да бъде записано"
                    }
                    saveButton.isEnabled = companies.isNotEmpty()
                }
            }.start()
            saveButton.setOnClickListener {
                if (!CrmContactSyncStore.isEnabled(activity.applicationContext, phone)) {
                    status.text = "Този контакт не е CRM маркиран."
                    return@setOnClickListener
                }
                val selected = companies.getOrNull(spinner.selectedItemPosition - 1)
                if (selected == null) {
                    status.text = "Избери преди запис"
                    return@setOnClickListener
                }
                val stored = SmsCompanyAssignmentStore.save(activity, phone, sms.providerId, selected.id)
                val queued = stored && CallReportTopicNoteOutbox.enqueueSms(activity, phone, sms, selected.id)
                if (!queued) {
                    status.text = "Не успях да запиша SMS към фирмата"
                    return@setOnClickListener
                }
                HomeCrmCompanyMembershipStore.invalidate(activity, phone)
                activity.sendBroadcast(Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(activity.packageName))
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
