package com.onlineimoti.calllog

import android.app.Activity
import android.widget.EditText
import java.util.concurrent.ExecutorService

/** Loads and switches note text between Local and every available company scope. */
internal class ContactNoteScopeTextController(
    private val activity: Activity,
    private val executor: ExecutorService,
    private val draft: () -> ContactNoteFormDraft,
    private val selectedCompanyId: () -> String,
    private val noteInput: () -> EditText?,
    private val isActive: () -> Boolean,
    private val initialScopeId: () -> String = { "" },
    private val initialValue: () -> ContactNoteScopeValue = { ContactNoteScopeValue() },
    private val onValueApplied: (companyId: String, value: ContactNoteScopeValue) -> Unit,
) {
    private var serverValues: Map<String, ContactNoteScopeValue>? = null
    private var loading = false
    private var displayedScopeId = ""
    private var displayedScopeValue = ContactNoteScopeValue()


    fun currentValue(): ContactNoteScopeValue = displayedScopeValue

    fun valueFor(companyId: String): ContactNoteScopeValue {
        val safeCompanyId = companyId.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        return ContactNoteScopeTextResolver.valueFor(
            companyId = safeCompanyId,
            draft = draft(),
            serverValues = serverValues ?: initialValues(),
            context = activity,
        )
    }
    fun refresh(companyId: String, input: EditText) {
        val safeCompanyId = companyId.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        val values = serverValues ?: initialValues()
        val value = ContactNoteScopeTextResolver.valueFor(
            companyId = safeCompanyId,
            draft = draft(),
            serverValues = values,
            context = activity,
        )
        replaceInputValue(input, safeCompanyId, value)
        if (safeCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID && serverValues == null) loadServerValues()
    }

    private fun initialValues(): Map<String, ContactNoteScopeValue> {
        val scope = initialScopeId().ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        val value = initialValue()
        return if (value.text.isBlank() && value.serverClientEventId.isBlank()) emptyMap() else mapOf(scope to value)
    }

    private fun loadServerValues() {
        if (loading) return
        loading = true
        executor.execute {
            val values = runCatching {
                ContactNoteScopeTextResolver.loadServerValues(activity.applicationContext, draft())
            }.getOrNull()
            activity.runOnUiThread {
                if (!isActive()) return@runOnUiThread
                loading = false
                if (values == null) return@runOnUiThread
                serverValues = values
                val input = noteInput() ?: return@runOnUiThread
                val selectedId = selectedCompanyId()
                if (
                    selectedId.isNotBlank() &&
                    selectedId != ContactNoteTopicState.LOCAL_COMPANY_ID &&
                    displayedScopeId == selectedId &&
                    input.text?.toString().orEmpty() == displayedScopeValue.text
                ) {
                    refresh(selectedId, input)
                }
            }
        }
    }

    private fun replaceInputValue(input: EditText, companyId: String, value: ContactNoteScopeValue) {
        if (input.text?.toString().orEmpty() != value.text) {
            input.setText(value.text)
            input.setSelection(input.text?.length ?: 0)
        }
        displayedScopeId = companyId
        displayedScopeValue = value
        onValueApplied(companyId, value)
    }
}
