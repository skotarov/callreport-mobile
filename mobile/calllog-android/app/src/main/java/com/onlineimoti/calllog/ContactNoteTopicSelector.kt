package com.onlineimoti.calllog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

internal data class ContactNoteTopicState(
    val visible: Boolean,
    val loading: Boolean = false,
    val companies: List<CallReportTopicCompany> = emptyList(),
    val selectedCompanyId: String = "",
    /** Main-note forms expose a private/personal option before server companies. */
    val includeLocalOption: Boolean = false,
    /** No server company is available for this form; keep only the personal option visible. */
    val localOnly: Boolean = false,
    /** Non-empty only when neither the server nor a cached company list could be loaded. */
    val loadError: String = "",
    /** The visible firms came from the last successful sync, not from a live request. */
    val usingCachedCompanies: Boolean = false,
    /** Timestamp of the cached company list, zero when the list is live or unavailable. */
    val cachedCompaniesUpdatedAtMs: Long = 0L,
) {
    companion object {
        /** Synthetic internal selection only; never sent to the server as a company id. */
        const val LOCAL_COMPANY_ID = "__callreport_local__"
    }
}

internal object ContactNoteTopicSelector {
    fun bind(
        context: Context,
        radioGroup: RadioGroup,
        state: ContactNoteTopicState,
        onSelected: (String) -> Unit,
    ) {
        val options = selectableOptions(context, state)
        val selectedCompanyId = resolvedSelectedCompanyId(state)
        val interactionEnabled = !state.loading &&
            state.loadError.isBlank() &&
            !state.localOnly &&
            options.size > 1

        radioGroup.setOnCheckedChangeListener(null)
        radioGroup.removeAllViews()
        radioGroup.clearCheck()
        radioGroup.isEnabled = interactionEnabled

        if (options.isEmpty()) {
            radioGroup.addView(statusText(context, statusLabel(context, state)))
            updateValidationBorder(context, radioGroup, state, selectedCompanyId, hasOptions = false)
            return
        }

        val optionByViewId = linkedMapOf<Int, String>()
        options.forEach { option ->
            val viewId = View.generateViewId()
            optionByViewId[viewId] = option.id
            radioGroup.addView(RadioButton(context).apply {
                id = viewId
                text = option.label
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.rgb(51, 65, 85))
                buttonTintList = radioTint()
                minHeight = dp(context, 42)
                setPadding(0, dp(context, 2), dp(context, 4), dp(context, 2))
                isEnabled = interactionEnabled
                if (option.serverBacked) {
                    androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_cloud_note_filled)?.mutate()?.let { cloud ->
                        cloud.setTint(context.getColor(R.color.callreport_icon_background))
                        cloud.setBounds(0, 0, dp(context, 17), dp(context, 17))
                        setCompoundDrawablesRelative(cloud, null, null, null)
                        compoundDrawablePadding = dp(context, 4)
                    }
                }
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.WRAP_CONTENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT,
                )
            })
        }

        val selectedViewId = optionByViewId.entries.firstOrNull { it.value == selectedCompanyId }?.key
        if (selectedViewId != null) radioGroup.check(selectedViewId)
        updateValidationBorder(context, radioGroup, state, selectedCompanyId, hasOptions = true)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = optionByViewId[checkedId].orEmpty()
            if (selected.isBlank()) return@setOnCheckedChangeListener
            updateValidationBorder(context, radioGroup, state, selected, hasOptions = true)
            onSelected(selected)
        }

        // Apply a resolved fallback only when it differs from the state supplied by
        // the host. Rebinding an already selected option must stay side-effect free:
        // note editors rebind after applying text, and calling onSelected again here
        // would recursively reload and rebind the same scope until the UI freezes.
        if (
            selectedCompanyId.isNotBlank() &&
            state.selectedCompanyId.trim() != selectedCompanyId
        ) {
            onSelected(selectedCompanyId)
        }
    }

    /**
     * Every visible note form starts from a real destination. Personal is preferred;
     * an existing allowed firm remains selected, while a removed permission falls
     * back to Personal instead of leaving a synthetic "Choose" row selected.
     */
    internal fun resolvedSelectedCompanyId(state: ContactNoteTopicState): String {
        val ids = buildList {
            if (state.includeLocalOption || state.localOnly) add(ContactNoteTopicState.LOCAL_COMPANY_ID)
            state.companies.mapTo(this) { it.id }
        }.filter { it.isNotBlank() }.distinct()
        val requested = state.selectedCompanyId.trim()
        return when {
            requested in ids -> requested
            ContactNoteTopicState.LOCAL_COMPANY_ID in ids -> ContactNoteTopicState.LOCAL_COMPANY_ID
            else -> ids.firstOrNull().orEmpty()
        }
    }

    private fun selectableOptions(context: Context, state: ContactNoteTopicState): List<TopicOption> {
        val serverOptions = state.companies.map { TopicOption(it.id, it.name, serverBacked = true) }
        return if (state.includeLocalOption || state.localOnly) {
            listOf(
                TopicOption(
                    ContactNoteTopicState.LOCAL_COMPANY_ID,
                    personalOptionLabel(),
                    serverBacked = false,
                ),
            ) + serverOptions
        } else {
            serverOptions
        }
    }

    private fun personalOptionLabel(): String =
        if (AppLocaleText.isBulgarian()) "Лична" else "Personal"

    private fun statusLabel(context: Context, state: ContactNoteTopicState): String = when {
        state.loading -> context.getString(R.string.dynamic_note_companies_loading)
        state.loadError.isNotBlank() -> context.getString(R.string.note_topics_unavailable_local_only)
        else -> context.getString(R.string.dynamic_note_no_company_destinations)
    }

    private fun statusText(context: Context, value: String): TextView = TextView(context).apply {
        text = value
        textSize = 14f
        setTextColor(Color.rgb(100, 116, 139))
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(context, 42)
        setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
        layoutParams = RadioGroup.LayoutParams(
            RadioGroup.LayoutParams.MATCH_PARENT,
            RadioGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun radioTint(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_enabled),
            intArrayOf(),
        ),
        intArrayOf(
            Color.rgb(37, 99, 235),
            Color.rgb(100, 116, 139),
            Color.rgb(148, 163, 184),
        ),
    )

    private fun updateValidationBorder(
        context: Context,
        radioGroup: RadioGroup,
        state: ContactNoteTopicState,
        selectedCompanyId: String,
        hasOptions: Boolean,
    ) {
        val field = radioGroup.parent as? LinearLayout ?: return
        if (field.tag != ContactNoteTopicFieldUi.FIELD_TAG) return

        val selectionRequired = !state.loading && state.loadError.isBlank() && !state.localOnly && hasOptions
        val missingSelection = selectionRequired && selectedCompanyId.isBlank()
        val density = context.resources.displayMetrics.density
        val strokeWidth = (if (missingSelection) 2 else 1) * density
        field.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * density
            setColor(Color.WHITE)
            setStroke(
                strokeWidth.toInt(),
                if (missingSelection) Color.rgb(220, 38, 38) else Color.rgb(209, 213, 219),
            )
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private data class TopicOption(
        val id: String,
        val label: String,
        val serverBacked: Boolean,
    )
}
