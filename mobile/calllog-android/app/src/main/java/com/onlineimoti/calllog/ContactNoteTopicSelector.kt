package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner

internal data class ContactNoteTopicState(
    val visible: Boolean,
    val loading: Boolean = false,
    val companies: List<CallReportTopicCompany> = emptyList(),
    val selectedCompanyId: String = "",
    /** Main-note forms expose a local-only option before server companies. */
    val includeLocalOption: Boolean = false,
    /** No server company is available for this form; keep only the Local option visible. */
    val localOnly: Boolean = false,
    /** Non-empty only when neither the server nor a cached company list could be loaded. */
    val loadError: String = "",
    /** The visible firms came from the last successful sync, not from a live request. */
    val usingCachedCompanies: Boolean = false,
    /** Timestamp of the cached company list, zero when the list is live or unavailable. */
    val cachedCompaniesUpdatedAtMs: Long = 0L,
) {
    companion object {
        /** Synthetic selection only; never sent to the server as a company id. */
        const val LOCAL_COMPANY_ID = "__callreport_local__"
    }
}

internal object ContactNoteTopicSelector {
    fun bind(
        context: Context,
        spinner: Spinner,
        state: ContactNoteTopicState,
        onSelected: (String) -> Unit,
    ) {
        val options = selectableOptions(context, state)
        val labels = when {
            state.loading && (state.includeLocalOption || state.localOnly) -> {
                listOf(context.getString(R.string.note_local_company))
            }
            state.loading -> listOf(context.getString(R.string.dynamic_note_companies_loading))
            state.loadError.isNotBlank() && (state.includeLocalOption || state.localOnly) -> {
                listOf(context.getString(R.string.note_local_company))
            }
            state.loadError.isNotBlank() -> listOf(context.getString(R.string.note_topics_unavailable_local_only))
            state.localOnly -> listOf(context.getString(R.string.note_local_company))
            options.isEmpty() -> listOf(context.getString(R.string.dynamic_note_no_company_destinations))
            else -> options.map { it.label }
        }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        // A single Local destination needs no interaction. Enable the spinner only
        // when the user can actually switch between Local and one or more firms.
        spinner.isEnabled = !state.loading &&
            state.loadError.isBlank() &&
            !state.localOnly &&
            options.size > 1

        val selectedCompanyId = resolvedSelectedCompanyId(state)
        val selectedIndex = options.indexOfFirst { it.id == selectedCompanyId }.coerceAtLeast(0)
        spinner.setSelection(selectedIndex, false)
        updateValidationBorder(context, spinner, state, selectedCompanyId, options.isNotEmpty())
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = options.getOrNull(position)?.id.orEmpty()
                updateValidationBorder(context, spinner, state, selected, options.isNotEmpty())
                onSelected(selected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                val fallback = resolvedSelectedCompanyId(state)
                updateValidationBorder(context, spinner, state, fallback, options.isNotEmpty())
                onSelected(fallback)
            }
        }
    }

    /**
     * Every visible note form starts from a real destination. Local is preferred;
     * an existing allowed firm remains selected, while a removed permission falls
     * back to Local instead of leaving a synthetic "Choose" row selected.
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
        val serverOptions = state.companies.map { TopicOption(it.id, it.name) }
        return if (state.includeLocalOption || state.localOnly) {
            listOf(TopicOption(ContactNoteTopicState.LOCAL_COMPANY_ID, context.getString(R.string.note_local_company))) + serverOptions
        } else {
            serverOptions
        }
    }

    private fun updateValidationBorder(
        context: Context,
        spinner: Spinner,
        state: ContactNoteTopicState,
        selectedCompanyId: String,
        hasOptions: Boolean,
    ) {
        val field = spinner.parent as? LinearLayout ?: return
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

    private data class TopicOption(
        val id: String,
        val label: String,
    )
}
