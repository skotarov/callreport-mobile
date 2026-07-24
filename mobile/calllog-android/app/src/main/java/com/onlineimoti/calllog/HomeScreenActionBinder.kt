package com.onlineimoti.calllog

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal object HomeScreenActionBinder {
    private const val BRAND_CONTAINER_TAG = "relationship_manager_brand_container"
    private const val CRM_SERVER_INDICATOR_TAG = "crm_server_enabled_indicator"

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
        installCrmServerIndicator(activity, binding)
        applyCompactBottomBarSpacing(binding)
        binding.settingsButton.setOnClickListener { openOverflow() }
        binding.crmModeButton.apply {
            setIconResource(R.drawable.ic_client_money)
            contentDescription = activity.getString(R.string.runtime_crm_clients)
            setOnClickListener { openCrmContacts() }
        }
        (binding.crmControlsScroll.getChildAt(1) as? TextView)?.setText(R.string.runtime_crm_clients)
        binding.crmControlsScroll.setOnClickListener { openCrmContacts() }
        binding.dialPadActionSlot.setOnClickListener { binding.dialPadButton.performClick() }
        binding.smsHistoryButton.setOnClickListener {
            activity.startActivity(Intent(activity, SmsHistoryActivity::class.java))
        }
        binding.smsHistoryActionSlot.setOnClickListener { binding.smsHistoryButton.performClick() }
        binding.relationshipManagerWordmark.apply {
            contentDescription = activity.getString(R.string.settings_registration_section)
            isClickable = true
            isFocusable = true
            setOnClickListener { openRegistration(activity) }
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
        val brandContainer = binding.relationshipManagerWordmark.parent as? View
        if (brandContainer?.tag == BRAND_CONTAINER_TAG) {
            brandContainer.visibility = if (visible) View.VISIBLE else View.GONE
        } else {
            binding.relationshipManagerWordmark.visibility = if (visible) View.VISIBLE else View.GONE
        }
        val indicator = (binding.relationshipManagerWordmark.parent as? ViewGroup)
            ?.findViewWithTag<TextView>(CRM_SERVER_INDICATOR_TAG)
        indicator?.visibility = if (visible && HomeCrmModeStore.isAvailable(binding.root.context)) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun installCrmServerIndicator(activity: AppCompatActivity, binding: ActivityHomeBinding) {
        val wordmark = binding.relationshipManagerWordmark
        val currentParent = wordmark.parent
        if ((currentParent as? View)?.tag == BRAND_CONTAINER_TAG) return
        val headerRow = currentParent as? LinearLayout ?: return
        val wordmarkPosition = headerRow.indexOfChild(wordmark)
        val brandLayoutParams = wordmark.layoutParams as? LinearLayout.LayoutParams ?: return
        val wordmarkHeight = brandLayoutParams.height
        val initialVisibility = wordmark.visibility

        headerRow.removeView(wordmark)
        val brandContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = BRAND_CONTAINER_TAG
            visibility = initialVisibility
            addView(
                wordmark,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    wordmarkHeight,
                ),
            )
            addView(
                createCrmServerIndicator(activity),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(activity, 6)
                },
            )
        }
        headerRow.addView(brandContainer, wordmarkPosition, brandLayoutParams)
    }

    private fun createCrmServerIndicator(activity: AppCompatActivity): TextView = TextView(activity).apply {
        tag = CRM_SERVER_INDICATOR_TAG
        text = "CRM"
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(51, 65, 85))
        setPadding(dp(activity, 7), dp(activity, 3), dp(activity, 7), dp(activity, 3))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = dp(activity, 12).toFloat()
            setStroke(dp(activity, 1), Color.rgb(148, 163, 184))
        }
        contentDescription = "CRM сървърът е включен"
        isClickable = false
        isFocusable = false
        visibility = View.GONE
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

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
