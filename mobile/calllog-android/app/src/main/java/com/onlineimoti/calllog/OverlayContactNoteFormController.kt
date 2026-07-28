package com.onlineimoti.calllog

import android.app.Service
import android.os.Handler
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Toast

/** Bridges the shared note workflow into the floating overlay editors. */
internal class OverlayContactNoteFormController(
    private val service: Service,
    private val handler: Handler,
    private val dp: (Int) -> Int,
    private val draft: ContactNoteFormDraft,
    preferredCompanyId: String = "",
) {
    private val topicFieldUi by lazy { ContactNoteTopicFieldUi(service, dp) }
    private var topicState = initialTopicState(preferredCompanyId)
    private var topicControl: RadioGroup? = null
    private var noteInput: EditText? = null
    private var serverScopeTexts: Map<String, String>? = null
    private var serverScopeTextLoading = false
    private var displayedScopeId = ""
    private var displayedScopeText = ""
    /** Updated after programmatic scope text changes and successful writes. */
    private var persistedText = ""

    fun addTopicFieldTo(container: LinearLayout, input: EditText) {
        noteInput = input
        persistedText = input.text?.toString().orEmpty()
        topicFieldUi.create(
            state = topicState,
            onSelected = { selected ->
                topicState = topicState.copy(selectedCompanyId = selected)
                if (draft.isGeneralNote) refreshTextForScope(selected)
            },
            onControlReady = { control -> topicControl = control },
        )?.let(container::addView)
        if (draft.isGeneralNote) refreshTextForScope(topicState.selectedCompanyId)
        if (topicState.visible) loadTopics()
    }

    /** Used by explicit Save: an eligible contact must deliberately choose Local or a firm. */
    fun save(noteText: String): ContactNoteFormSaveResult? {
        val topicId = if (draft.serverClientEventId.isNotBlank()) {
            // Existing server notes are mutated by their original client_event_id;
            // an unfinished company selector must never block that edit.
            effectiveCompanyId().ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        } else {
            ContactNoteFormWorkflow.selectedTopicOrLocalFallback(topicState) ?: run {
                Toast.makeText(service, service.getString(R.string.note_company_required), Toast.LENGTH_SHORT).show()
                return null
            }
        }
        return saveToTopic(noteText, topicId, localOnlyFallback = topicState.loadError.isNotBlank())
    }

    /**
     * Used before switching editor/modal or closing it. While the firm list is
     * still loading or no option has been selected, preserve changed text locally
     * and keep the existing deferred-company marker instead of losing the draft.
     */
    fun saveForTransition(noteText: String): ContactNoteFormSaveResult {
        val explicitDestination = effectiveCompanyId()
        val strictDestination = ContactNoteFormWorkflow.selectedTopicOrLocalFallback(topicState)
        val mustFallbackLocally = strictDestination == null && explicitDestination.isBlank()
        val destination = strictDestination
            ?: explicitDestination.takeIf { it.isNotBlank() }
            ?: ContactNoteTopicState.LOCAL_COMPANY_ID
        return saveToTopic(
            noteText = noteText,
            topicId = destination,
            localOnlyFallback = topicState.loadError.isNotBlank() || mustFallbackLocally,
        )
    }

    fun hasChangedText(noteText: String): Boolean = noteText != persistedText

    fun markTextPersisted(noteText: String) {
        persistedText = noteText
    }

    /** Carries a concrete company across the blue/yellow overlay forms. */
    fun effectiveCompanyId(): String = topicState.selectedCompanyId.trim()

    private fun initialTopicState(preferredCompanyId: String): ContactNoteTopicState {
        val base = ContactNoteFormWorkflow.initialTopicState(service, draft)
        val preferred = preferredCompanyId.trim()
        if (preferred.isBlank() || !base.visible) return base
        // A previously assigned company must remain selectable even if the CRM
        // switch is currently off for that Android contact.
        return base.copy(
            loading = true,
            localOnly = false,
            selectedCompanyId = preferred,
        )
    }

    private fun saveToTopic(
        noteText: String,
        topicId: String,
        localOnlyFallback: Boolean,
    ): ContactNoteFormSaveResult {
        return ContactNoteFormWorkflow.save(
            context = service,
            draft = draft,
            noteText = noteText,
            topicCompanyId = topicId,
            localOnlyFallback = localOnlyFallback,
        )
    }

    private fun loadTopics() {
        val stateAtStart = topicState
        Thread {
            val loadedState = ContactNoteFormWorkflow.loadTopics(service.applicationContext, stateAtStart)
            handler.post {
                topicState = loadedState
                topicControl?.let { control ->
                    ContactNoteTopicSelector.bind(service, control, topicState) { selected ->
                        topicState = topicState.copy(selectedCompanyId = selected)
                        if (draft.isGeneralNote) refreshTextForScope(selected)
                    }
                }
                if (draft.isGeneralNote) refreshTextForScope(topicState.selectedCompanyId)
            }
        }.start()
    }

    private fun refreshTextForScope(companyId: String) {
        val input = noteInput ?: return
        val safeCompanyId = companyId.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        val value = ContactNoteScopeTextResolver.textFor(
            companyId = safeCompanyId,
            draft = draft,
            serverTexts = serverScopeTexts,
            context = service,
        )
        replaceInputText(input, safeCompanyId, value)
        if (safeCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID && serverScopeTexts == null) {
            loadServerScopeTexts()
        }
    }

    private fun loadServerScopeTexts() {
        if (serverScopeTextLoading) return
        serverScopeTextLoading = true
        Thread {
            val values = runCatching {
                ContactNoteScopeTextResolver.loadServerTexts(service.applicationContext, draft)
            }.getOrNull()
            handler.post {
                serverScopeTextLoading = false
                if (values == null) return@post
                serverScopeTexts = values
                val input = noteInput ?: return@post
                val selectedCompanyId = topicState.selectedCompanyId
                if (
                    selectedCompanyId.isNotBlank() &&
                    selectedCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID &&
                    displayedScopeId == selectedCompanyId &&
                    input.text?.toString().orEmpty() == displayedScopeText
                ) {
                    refreshTextForScope(selectedCompanyId)
                }
            }
        }.start()
    }

    private fun replaceInputText(input: EditText, companyId: String, value: String) {
        if (input.text?.toString().orEmpty() != value) {
            input.setText(value)
            input.setSelection(input.text?.length ?: 0)
        }
        displayedScopeId = companyId
        displayedScopeText = value
        persistedText = value
    }
}
