package com.onlineimoti.calllog

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.SettingsGroupRegistrationBinding
import java.util.WeakHashMap

/** Makes company identity readable inline and keeps rare destructive actions unobtrusive. */
internal object RegistrationCompanyDetailsUi {
    private val installedLists = WeakHashMap<LinearLayout, Boolean>()

    fun install(activity: AppCompatActivity, binding: SettingsGroupRegistrationBinding) {
        binding.registrationServerAddressButton.visibility = View.GONE
        val list = binding.registrationCompaniesList
        if (installedLists.put(list, true) == null) {
            list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    list.post { decorate(activity, binding) }
                }

                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
            })
        }
        list.post { decorate(activity, binding) }
    }

    private fun decorate(activity: AppCompatActivity, binding: SettingsGroupRegistrationBinding) {
        if (activity.isFinishing || activity.isDestroyed) return
        val snapshot = runCatching {
            CallReportTopicCompaniesRepository.load(
                activity.applicationContext,
                ConfigStore.load(activity.applicationContext),
            )
        }.getOrNull() ?: return

        val list = binding.registrationCompaniesList
        snapshot.companies.forEachIndexed { index, company ->
            val row = list.getChildAt(index) as? LinearLayout ?: return@forEachIndexed
            val details = row.getChildAt(1) as? TextView ?: return@forEachIndexed
            val existingRole = details.text?.toString().orEmpty()
                .lineSequence()
                .firstOrNull { it.startsWith("Роля: ") }
                ?.substringAfter("Роля: ")
                ?.trim()
                .orEmpty()
                .ifBlank { details.text?.toString().orEmpty().trim() }
            details.text = buildString {
                append("ЕИК: ").append(company.eik.ifBlank { "—" })
                append("\nКод: ").append(company.id)
                append("\nРоля: ").append(existingRole)
            }
            details.setPadding(0, dp(activity, 5), 0, 0)
            details.setLineSpacing(0f, 1.12f)
            removeInformationButtons(activity, row)
            compactDeleteButton(activity, row)
        }
    }

    private fun removeInformationButtons(activity: AppCompatActivity, group: ViewGroup) {
        val label = activity.getString(R.string.settings_registration_company_information)
        for (index in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(index)
            if (child is MaterialButton && child.text?.toString() == label) {
                group.removeViewAt(index)
            } else if (child is ViewGroup) {
                removeInformationButtons(activity, child)
            }
        }
    }

    private fun compactDeleteButton(activity: AppCompatActivity, group: ViewGroup) {
        val label = activity.getString(R.string.settings_registration_company_delete)
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is MaterialButton && child.text?.toString() == label) {
                child.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(activity, 6) }
                child.minimumWidth = 0
                child.minimumHeight = dp(activity, 36)
                child.textSize = 12f
                child.setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
                child.requestLayout()
            } else if (child is ViewGroup) {
                compactDeleteButton(activity, child)
            }
        }
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
