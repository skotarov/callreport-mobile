package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.SettingsGroupRegistrationBinding
import java.util.WeakHashMap

/** Makes company identity readable inline and keeps rare destructive actions unobtrusive. */
internal object RegistrationCompanyDetailsUi {
    private val installedLists = WeakHashMap<LinearLayout, Boolean>()
    private val installedRoots = WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>()
    private val styledDangerButtons = WeakHashMap<MaterialButton, Boolean>()

    fun install(activity: AppCompatActivity, binding: SettingsGroupRegistrationBinding) {
        binding.registrationServerAddressButton.visibility = View.GONE
        installDangerButtonObserver(activity)
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
            styleDangerousButtons(activity, row)
        }
    }

    private fun installDangerButtonObserver(activity: AppCompatActivity) {
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        if (installedRoots.containsKey(root)) return
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (!activity.isFinishing && !activity.isDestroyed) {
                styleDangerousButtons(activity, root)
            }
        }
        installedRoots[root] = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
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

    private fun styleDangerousButtons(activity: AppCompatActivity, view: View) {
        if (view is MaterialButton && isDangerousButton(activity, view)) {
            styleDangerButton(activity, view)
            return
        }
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            styleDangerousButtons(activity, view.getChildAt(index))
        }
    }

    private fun isDangerousButton(activity: AppCompatActivity, button: MaterialButton): Boolean {
        val text = button.text?.toString().orEmpty()
        return text == activity.getString(R.string.settings_registration_company_delete) ||
            text == activity.getString(R.string.settings_registration_deactivate_user)
    }

    private fun styleDangerButton(activity: AppCompatActivity, button: MaterialButton) {
        if (styledDangerButtons.put(button, true) != null) return
        val errorColor = ContextCompat.getColor(activity, R.color.calllog_error)
        button.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(activity, 6) }
        button.minimumWidth = 0
        button.minimumHeight = dp(activity, 36)
        button.textSize = 12f
        button.isAllCaps = false
        button.setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
        button.setTextColor(errorColor)
        button.strokeColor = ColorStateList.valueOf(errorColor)
        button.strokeWidth = dp(activity, 1)
        button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        button.requestLayout()
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
