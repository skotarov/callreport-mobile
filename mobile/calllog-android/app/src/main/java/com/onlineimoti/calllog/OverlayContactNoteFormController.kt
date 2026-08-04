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
    private val onMoveCompleted: (ContactNoteMoveResult) -> Unit = {},
) {
    private val topicFieldUi by lazy { ContactNoteTopicFieldUi(service, dp) }
    private var topicState = initialTopicState(preferredCompanyId)
    private var topicControl: RadioGroup? = null
    private var noteInput: EditText? = null
    private var serverScopeValues: Map<String, ContactNoteScopeValue>? = null
    private var serverScopeValueLoading = false
    private var displayedScopeId = ""
    private var displayedScopeValue = ContactNoteScopeValue()
    private var persistedText = ""
    private var moveMode = false
    private var moveInProgress = false
    private var moveSourceCompanyId = ""

    fun addTopicFieldTo(container: LinearLayout, input: EditText) {
        noteInput = input
        persistedText = input.text?.toString().orEmpty()
        displayedScopeId = topicState.selectedCompanyId.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        displayedScopeValue = ContactNoteScopeValue(
            text = persistedText,
            serverClientEventId = draft.serverClientEventId,
            confirmedServer = draft.serverClientEventId.isNotBlank() &&
                ServerRecordIndex.isConfirmed(service, draft.serverClientEventId),
        )
        topicFieldUi.create(
            state = topicState,
            onSelected = ::selectCompany,
            onControlReady = { control -> topicControl = control },
            moveState = moveUiState(),
            onMoveAction = ::toggleMoveMode,
        )?.let(container::addView)
        if (draft.isGeneralNote) refreshTextForScope(topicState.selectedCompanyId)
        else if (topicState.selectedCompanyId.isNotBlank() &&
            topicState.selectedCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID) {
            loadServerScopeValues()
        }
        if (topicState.visible) loadTopics()
    }

    fun save(noteText: String): ContactNoteFormSaveResult? {
        val topicId = if (draft.serverClientEventId.isNotBlank()) {
            effectiveCompanyId().ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        } else {
            ContactNoteFormWorkflow.selectedTopicOrLocalFallback(topicState) ?: run {
                Toast.makeText(service, service.getString(R.string.note_company_required), Toast.LENGTH_SHORT).show()
                return null
            }
        }
        return saveToTopic(noteText, topicId, localOnlyFallback = topicState.loadError.isNotBlank())
    }

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

    fun effectiveCompanyId(): String = topicState.selectedCompanyId.trim()

    private fun initialTopicState(preferredCompanyId: String): ContactNoteTopicState {
        val base = ContactNoteFormWorkflow.initialTopicState(service, draft)
        val preferred = preferredCompanyId.trim()
        if (preferred.isBlank() || !base.visible) return base
        return base.copy(loading = true, localOnly = false, selectedCompanyId = preferred)
    }

    private fun saveToTopic(noteText: String, topicId: String, localOnlyFallback: Boolean): ContactNoteFormSaveResult =
        ContactNoteFormWorkflow.save(
            context = service,
            draft = draft,
            noteText = noteText,
            topicCompanyId = topicId,
            localOnlyFallback = localOnlyFallback,
        )

    private fun loadTopics() {
        val stateAtStart = topicState
        Thread {
            val loadedState = ContactNoteFormWorkflow.loadTopics(service.applicationContext, stateAtStart)
            handler.post {
                topicState = loadedState
                bindTopicControl()
                refreshTextForScope(topicState.selectedCompanyId)
            }
        }.start()
    }

    private fun bindTopicControl() {
        val control = topicControl ?: return
        topicFieldUi.bind(
            control = control,
            state = topicState,
            onSelected = ::selectCompany,
            moveState = moveUiState(),
            onMoveAction = ::toggleMoveMode,
        )
    }

    private fun selectCompany(selected: String) {
        if (moveMode) {
            moveToCompany(selected)
            return
        }
        topicState = topicState.copy(selectedCompanyId = selected)
        refreshTextForScope(selected)
    }

    private fun moveUiState(): ContactNoteMoveUiState = ContactNoteMoveUiState(
        canMove = !moveInProgress && ContactNoteMovePolicy.canStart(
            selectedCompanyId = topicState.selectedCompanyId,
            value = displayedScopeValue,
            currentText = noteInput?.text?.toString().orEmpty(),
            companyCount = topicState.companies.size,
        ),
        selectingTarget = moveMode,
        moving = moveInProgress,
        sourceCompanyId = moveSourceCompanyId,
    )

    private fun toggleMoveMode() {
        if (moveInProgress) return
        if (moveMode) {
            moveMode = false
            moveSourceCompanyId = ""
        } else if (moveUiState().canMove) {
            moveMode = true
            moveSourceCompanyId = topicState.selectedCompanyId
        }
        bindTopicControl()
    }

    private fun moveToCompany(targetCompanyId: String) {
        val sourceCompanyId = moveSourceCompanyId
        if (!ContactNoteMovePolicy.canTarget(sourceCompanyId, targetCompanyId)) {
            Toast.makeText(service, "Избери друга фирма", Toast.LENGTH_SHORT).show()
            bindTopicControl()
            return
        }
        val input = noteInput ?: return
        val sourceValue = displayedScopeValue
        val targetValue = valueFor(targetCompanyId)
        if (targetValue.text.isNotBlank() && !targetValue.confirmedServer) {
            Toast.makeText(service, "Изчакай бележката в целевата фирма да се синхронизира", Toast.LENGTH_SHORT).show()
            bindTopicControl()
            return
        }
        val sourceText = input.text?.toString().orEmpty().trim()
        if (!sourceValue.confirmedServer || sourceValue.serverClientEventId.isBlank() || sourceText.isBlank()) {
            Toast.makeText(service, "Бележката още не е готова за преместване", Toast.LENGTH_SHORT).show()
            moveMode = false
            moveSourceCompanyId = ""
            bindTopicControl()
            return
        }

        moveInProgress = true
        bindTopicControl()
        val request = ContactNoteMoveRequest(
            noteKind = if (draft.isGeneralNote) "main" else "call",
            sourceCompanyId = sourceCompanyId,
            targetCompanyId = targetCompanyId,
            sourceClientEventId = sourceValue.serverClientEventId,
            targetClientEventId = targetValue.serverClientEventId.takeIf { targetValue.confirmedServer }.orEmpty(),
            phone = draft.phone,
            sourceNote = sourceText,
        )
        Thread {
            val result = runCatching { ContactNoteMoveClient.move(service.applicationContext, request) }
            handler.post {
                result.onSuccess { moved ->
                    ContactNoteMoveClient.applyLocalResult(
                        service.applicationContext,
                        draft,
                        sourceCompanyId,
                        sourceValue.serverClientEventId,
                        moved,
                    )
                    Toast.makeText(
                        service,
                        "Бележката е преместена в ${moved.targetCompanyName.ifBlank { companyName(moved.targetCompanyId) }}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    onMoveCompleted(moved)
                }.onFailure { error ->
                    moveInProgress = false
                    moveMode = true
                    bindTopicControl()
                    Toast.makeText(
                        service,
                        error.message.orEmpty().ifBlank { "Бележката не можа да бъде преместена" },
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun companyName(companyId: String): String =
        topicState.companies.firstOrNull { it.id == companyId }?.name.orEmpty().ifBlank { "другата фирма" }

    private fun refreshTextForScope(companyId: String) {
        val input = noteInput ?: return
        val safeCompanyId = companyId.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        val value = valueFor(safeCompanyId)
        replaceInputValue(input, safeCompanyId, value)
        if (safeCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID && serverScopeValues == null) {
            loadServerScopeValues()
        }
    }

    private fun valueFor(companyId: String): ContactNoteScopeValue = ContactNoteScopeTextResolver.valueFor(
        companyId = companyId.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID },
        draft = draft,
        serverValues = serverScopeValues,
        context = service,
    )

    private fun loadServerScopeValues() {
        if (serverScopeValueLoading) return
        serverScopeValueLoading = true
        Thread {
            val values = runCatching {
                ContactNoteScopeTextResolver.loadServerValues(service.applicationContext, draft)
            }.getOrNull()
            handler.post {
                serverScopeValueLoading = false
                if (values == null) return@post
                serverScopeValues = values
                val input = noteInput ?: return@post
                val selectedCompanyId = topicState.selectedCompanyId
                if (
                    selectedCompanyId.isNotBlank() &&
                    selectedCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID &&
                    displayedScopeId == selectedCompanyId &&
                    input.text?.toString().orEmpty() == displayedScopeValue.text
                ) {
                    refreshTextForScope(selectedCompanyId)
                } else {
                    bindTopicControl()
                }
            }
        }.start()
    }

    private fun replaceInputValue(input: EditText, companyId: String, value: ContactNoteScopeValue) {
        if (input.text?.toString().orEmpty() != value.text) {
            input.setText(value.text)
            input.setSelection(input.text?.length ?: 0)
        }
        displayedScopeId = companyId
        displayedScopeValue = value
        persistedText = value.text
        bindTopicControl()
    }
}
