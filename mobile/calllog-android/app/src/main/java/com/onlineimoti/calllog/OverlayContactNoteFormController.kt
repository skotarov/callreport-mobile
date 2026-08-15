package com.onlineimoti.calllog

import android.app.Service
import android.os.Handler
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout

/** Bridges the shared multi-scope note workflow into the floating overlay editor. */
internal class OverlayContactNoteFormController(
    private val service: Service,
    private val handler: Handler,
    private val dp: (Int) -> Int,
    private val draft: ContactNoteFormDraft,
    preferredCompanyId: String = "",
) {
    private val fieldsUi by lazy { ContactNoteMultiScopeFieldsUi(service, dp) }
    private var topicState = initialTopicState(preferredCompanyId)
    private var fieldsContainer: LinearLayout? = null
    private var focusedScopeId = ContactNoteTopicState.LOCAL_COMPANY_ID
    private val scopeInputs = linkedMapOf<String, EditText>()
    private val scopeTexts = linkedMapOf<String, String>()
    private val persistedScopeValues = linkedMapOf<String, ContactNoteScopeValue>()
    private var serverScopeValues: Map<String, ContactNoteScopeValue>? = null

    fun addTopicFieldTo(container: LinearLayout, legacyInput: EditText) {
        val localId = ContactNoteTopicState.LOCAL_COMPANY_ID
        val initialText = legacyInput.text?.toString().orEmpty()
        legacyInput.visibility = View.GONE

        val localValue = ContactNoteScopeTextResolver.cachedValue(service, draft, localId)
        persistedScopeValues[localId] = localValue
        scopeTexts[localId] = localValue.text

        val initialScopeId = topicState.selectedCompanyId.ifBlank { localId }
        if (initialScopeId == localId) {
            if (initialText.isNotBlank() && localValue.text.isBlank()) {
                persistedScopeValues[localId] = localValue.copy(text = initialText)
                scopeTexts[localId] = initialText
            }
        } else if (initialText.isNotBlank() || draft.serverClientEventId.isNotBlank()) {
            val initialValue = ContactNoteScopeValue(
                text = initialText,
                serverClientEventId = draft.serverClientEventId,
                confirmedServer = draft.serverClientEventId.isNotBlank() &&
                    ServerRecordIndex.isConfirmed(service, draft.serverClientEventId),
            )
            persistedScopeValues[initialScopeId] = initialValue
            scopeTexts[initialScopeId] = initialText
        }

        val fields = fieldsUi.create(
            state = topicState,
            kind = if (draft.isGeneralNote) UnifiedNoteKind.GENERAL else UnifiedNoteKind.CALL,
            textFor = ::textForScope,
            onInputReady = ::onScopeInputReady,
        )
        fieldsContainer = fields
        container.addView(fields)
        if (topicState.visible) loadTopics()
    }

    fun saveAll(): Boolean {
        captureScopeTexts()
        for (companyId in scopeIds()) {
            val text = scopeTexts[companyId].orEmpty()
            val persisted = persistedScopeValues[companyId] ?: ContactNoteScopeValue()
            if (text == persisted.text) continue

            val result = ContactNoteFormWorkflow.save(
                context = service,
                draft = draft.copy(serverClientEventId = persisted.serverClientEventId),
                noteText = text,
                topicCompanyId = companyId,
                localOnlyFallback = false,
            )
            if (!result.saved) return false
            persistedScopeValues[companyId] = persisted.copy(text = text)
        }
        return true
    }

    fun hasChanges(): Boolean {
        captureScopeTexts()
        return scopeIds().any { companyId ->
            scopeTexts[companyId].orEmpty() != persistedScopeValues[companyId]?.text.orEmpty()
        }
    }

    fun focusedText(): String {
        captureScopeTexts()
        return scopeTexts[focusedScopeId]
            ?: scopeTexts[ContactNoteTopicState.LOCAL_COMPANY_ID]
            .orEmpty()
    }

    fun effectiveCompanyId(): String = focusedScopeId

    fun focusInput(): EditText? =
        scopeInputs[focusedScopeId] ?: scopeInputs[ContactNoteTopicState.LOCAL_COMPANY_ID]

    private fun initialTopicState(preferredCompanyId: String): ContactNoteTopicState {
        val base = ContactNoteFormWorkflow.initialTopicState(service, draft)
        val preferred = preferredCompanyId.trim()
        if (preferred.isBlank() || !base.visible) return base
        return base.copy(loading = true, localOnly = false, selectedCompanyId = preferred)
    }

    private fun loadTopics() {
        val stateAtStart = topicState
        Thread {
            val loadedState = ContactNoteFormWorkflow.loadTopics(service.applicationContext, stateAtStart)
            val loadedValues = if (loadedState.companies.isNotEmpty()) {
                runCatching {
                    ContactNoteScopeTextResolver.loadServerValues(service.applicationContext, draft)
                }.getOrNull()
            } else null
            handler.post {
                captureScopeTexts()
                topicState = loadedState
                serverScopeValues = loadedValues
                topicState.companies.forEach { company ->
                    val value = ContactNoteScopeTextResolver.valueFor(
                        companyId = company.id,
                        draft = draft,
                        serverValues = loadedValues,
                        context = service,
                    )
                    if (!persistedScopeValues.containsKey(company.id) || scopeTexts[company.id] == persistedScopeValues[company.id]?.text) {
                        persistedScopeValues[company.id] = value
                        if (!scopeTexts.containsKey(company.id)) scopeTexts[company.id] = value.text
                    }
                }
                bindAllFields()
            }
        }.start()
    }

    private fun bindAllFields() {
        val container = fieldsContainer ?: return
        captureScopeTexts()
        scopeInputs.clear()
        fieldsUi.bind(
            container = container,
            state = topicState,
            kind = if (draft.isGeneralNote) UnifiedNoteKind.GENERAL else UnifiedNoteKind.CALL,
            textFor = ::textForScope,
            onInputReady = ::onScopeInputReady,
        )
    }

    private fun onScopeInputReady(companyId: String, input: EditText) {
        scopeInputs[companyId] = input
        if (!scopeTexts.containsKey(companyId)) scopeTexts[companyId] = input.text?.toString().orEmpty()
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focusedScopeId = companyId
        }
    }

    private fun captureScopeTexts() {
        scopeInputs.forEach { (companyId, input) ->
            scopeTexts[companyId] = input.text?.toString().orEmpty()
        }
    }

    private fun textForScope(companyId: String): String {
        scopeTexts[companyId]?.let { return it }
        val value = persistedScopeValues[companyId] ?: ContactNoteScopeTextResolver.valueFor(
            companyId = companyId,
            draft = draft,
            serverValues = serverScopeValues,
            context = service,
        ).also { persistedScopeValues[companyId] = it }
        return value.text
    }

    private fun scopeIds(): List<String> = buildList {
        add(ContactNoteTopicState.LOCAL_COMPANY_ID)
        topicState.companies.mapTo(this) { it.id }
    }.filter { it.isNotBlank() }.distinct()
}
