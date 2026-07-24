package com.onlineimoti.calllog

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal object HomeScreenActionBinder {
    fun wire(
        activity: AppCompatActivity,
        binding: ActivityHomeBinding,
        openOverflow: () -> Unit,
        openCrmContacts: () -> Unit,
        previousPage: () -> Unit,
        nextPage: () -> Unit,
        isOnLaterPage: () -> Boolean,
        goToFirstPage: () -> Unit,
    ) {
        HomeResumeRefreshController.install(activity, binding)
        applyCompactBottomBarSpacing(binding)
        binding.settingsButton.setOnClickListener { openOverflow() }
        binding.crmModeButton.apply {
            setIconResource(R.drawable.ic_settings_registration)
            contentDescription = activity.getString(R.string.settings_registration_section)
            setOnClickListener { openRegistration(activity) }
        }
        (binding.crmControlsScroll.getChildAt(1) as? TextView)?.setText(R.string.settings_registration_section)
        binding.crmControlsScroll.setOnClickListener { openRegistration(activity) }
        binding.dialPadActionSlot.setOnClickListener { binding.dialPadButton.performClick() }
        binding.smsHistoryButton.setOnClickListener {
            activity.startActivity(Intent(activity, SmsHistoryActivity::class.java))
        }
        binding.smsHistoryActionSlot.setOnClickListener { binding.smsHistoryButton.performClick() }
        binding.relationshipManagerWordmark.apply {
            contentDescription = activity.getString(R.string.runtime_crm_clients)
            isClickable = true
            isFocusable = true
            setOnClickListener { openCrmContacts() }
        }
        binding.clearFilterButton.visibility = View.GONE
        binding.filteredDialButton.visibility = View.GONE
        binding.previousCallsButton.setOnClickListener { previousPage() }
        binding.nextCallsButton.setOnClickListener { nextPage() }
        binding.pageText.setOnClickListener {
            if (!isOnLaterPage()) return@setOnClickListener
            AlertDialog.Builder(activity)
                .setTitle("Връщане към началото")
                .setMessage("Да отида ли на страница 1?")
                .setNegativeButton("Отказ", null)
                .setPositiveButton("Да") { _, _ -> goToFirstPage() }
                .show()
        }
    }

    fun updateBrandShortcutVisibility(binding: ActivityHomeBinding, visible: Boolean) {
        binding.relationshipManagerWordmark.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun openRegistration(activity: AppCompatActivity) {
        activity.startActivity(Intent(activity, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_REGISTRATION, true)
        })
    }

    private fun applyCompactBottomBarSpacing(binding: ActivityHomeBinding) {
        val spacing = (3f * binding.root.resources.displayMetrics.density).toInt()
        binding.root.setPaddingRelative(
            binding.root.paddingStart,
            binding.root.paddingTop,
            binding.root.paddingEnd,
            spacing,
        )
        val params = binding.homeBottomActionBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.topMargin != spacing) {
            params.topMargin = spacing
            binding.homeBottomActionBar.layoutParams = params
        }
    }
}
