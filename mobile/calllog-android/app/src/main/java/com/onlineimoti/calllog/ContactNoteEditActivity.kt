package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import java.util.concurrent.Executors

class ContactNoteEditActivity : FontScaledActivity() {
    private var phone = ""
    private var titleText = ""
    private var direction = ""
    private var callAt = 0L
    private var durationSeconds = 0L
    private var actionIssuedAt = 0L
    private var isGeneralNote = false
    private var preferredCompanyId = ""
    private var initialNoteText = ""
    private var initialServerClientEventId = ""
    private var callServerClientEventId = ""
    private var generalServerClientEventId = ""
    private var serverClientEventId = ""
    private var topicState = ContactNoteTopicState(visible = false)
    private var topicControl: RadioGroup? = null
    private var noteInput: EditText? = null
    private var persistedEditorText = ""
    private var editorGeneration = 0
    private var scopeTextController: ContactNoteScopeTextController? = null
    private var currentScopeValue = ContactNoteScopeValue()
    private var moveMode = false
    private var moveInProgress = false
    private var moveSourceCompanyId = ""
    private val topicExecutor = Executors.newSingleThreadExecutor()
    private val saveController by lazy {
        ContactNoteEditSaveController(
            activity = this,
            draft = ::draft,
            topicState = { topicState },
            applyTarget = { target ->
                direction = target.direction
                callAt = target.callAt
                durationSeconds = target.durationSeconds
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_SHOW_NUMBER_KEYPAD, false)) {
            setContentView(NumberEntryUi(
                activity = this,
                onNumberConfirmed = { number ->
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_NUMBER, number))
                    finish()
                },
                close = { finish() },
            ).buildContent())
            return
        }
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
        readDraftFromIntent()
        topicState = initialTopicState()
        renderEditor()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (intent.getBooleanExtra(EXTRA_SHOW_NUMBER_KEYPAD, false)) {
            super.onBackPressed()
            return
        }
        if (moveMode && !moveInProgress) {
            cancelMoveMode()
            return
        }
        requestCloseWithoutSaving(noteInput?.text?.toString().orEmpty())
    }

    override fun onDestroy() {
        editorGeneration += 1
        topicExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun renderEditor() {
        editorGeneration += 1
        val generation = editorGeneration
        topicControl = null
        noteInput = null
        scopeTextController = ContactNoteScopeTextController(
            activity = this,
            executor = topicExecutor,
            draft = ::draft,
            selectedCompanyId = { topicState.selectedCompanyId },
            noteInput = { noteInput },
            isActive = { generation == editorGeneration && !isFinishing && !isDestroyed },
            initialScopeId = { preferredCompanyId.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID } },
            initialValue = {
                ContactNoteScopeValue(
                    text = initialTextForScope(),
                    serverClientEventId = serverClientEventId,
                    confirmedServer = serverClientEventId.isNotBlank() &&
                        ServerRecordIndex.isConfirmed(this, serverClientEventId),
                )
            },
            onValueApplied = { _, value ->
                currentScopeValue = value
                persistedEditorText = value.text
                serverClientEventId = value.serverClientEventId
                storeCurrentServerEventId(value.serverClientEventId)
                if (!isGeneralNote) initialNoteText = value.text
                topicControl?.let(::bindTopicControl)
            },
        )
        setContentView(ContactNoteEditUi(
            activity = this,
            state = ::uiState,
            onTopicSelected = ::selectTopicCompany,
            onNoteInputReady = { input ->
                noteInput = input
                persistedEditorText = input.text?.toString().orEmpty()
                if (topicState.visible) scopeTextController?.refresh(topicState.selectedCompanyId, input)
            },
            onTopicControlReady = { topicControl = it },
            onMoveAction = ::toggleMoveMode,
            saveAndSwitch = ::saveAndSwitch,
            saveAndClose = ::saveAndClose,
            deleteAndClose = ::deleteSelectedNote,
            saveAndOpenCalendar = ::saveAndOpenCalendar,
            close = ::requestCloseWithoutSaving,
        ).buildContent())
        if (topicState.visible) loadTopicCompanies(generation)
    }

    private fun readDraftFromIntent() {
        phone = intent.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty()
        titleText = intent.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty().ifBlank {
            phone.ifBlank { getString(R.string.dynamic_note_default_title) }
        }
        direction = intent.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty()
        callAt = intent.getLongExtra(PostCallOverlayService.EXTRA_CALL_AT, 0L)
        durationSeconds = intent.getLongExtra(PostCallOverlayService.EXTRA_DURATION, 0L)
        actionIssuedAt = intent.getLongExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, 0L)
        isGeneralNote = intent.getStringExtra(PostCallOverlayService.EXTRA_MODE) == PostCallOverlayService.MODE_GENERAL_NOTE
        preferredCompanyId = intent.getStringExtra(CompanyMainNoteEditorLauncher.EXTRA_COMPANY_ID).orEmpty().trim()
        initialNoteText = if (isGeneralNote) "" else intent.getStringExtra(CallNoteEditorLauncher.EXTRA_INITIAL_NOTE_TEXT).orEmpty()
        initialServerClientEventId = intent.getStringExtra(CallNoteEditorLauncher.EXTRA_SERVER_CLIENT_EVENT_ID).orEmpty().trim()
        if (isGeneralNote) generalServerClientEventId = initialServerClientEventId
        else callServerClientEventId = initialServerClientEventId
        serverClientEventId = currentServerEventId()
    }

    private fun initialTopicState(): ContactNoteTopicState {
        val base = ContactNoteFormWorkflow.initialTopicState(this, draft())
        return when {
            preferredCompanyId.isNotBlank() -> base.copy(
                selectedCompanyId = preferredCompanyId,
                localOnly = false,
                loading = base.visible,
            )
            !isGeneralNote && initialNoteText.isNotBlank() && base.visible && !base.localOnly ->
                base.copy(selectedCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID)
            else -> base
        }
    }

    private fun draft() = ContactNoteFormDraft(
        phone = phone,
        title = titleText,
        direction = direction,
        callAt = callAt,
        durationSeconds = durationSeconds,
        actionIssuedAt = actionIssuedAt,
        isGeneralNote = isGeneralNote,
        serverClientEventId = serverClientEventId,
    )

    private fun uiState() = ContactNoteEditUiState(
        phone = phone,
        titleText = titleText,
        direction = direction,
        callAt = callAt,
        durationSeconds = durationSeconds,
        isGeneralNote = isGeneralNote,
        topic = topicState,
        willEnableServerSync = ContactNoteFormWorkflow.willEnableServerSync(this, draft(), topicState),
        initialNoteText = if (isGeneralNote) "" else initialNoteText,
        move = moveUiState(),
    )

    private fun loadTopicCompanies(generation: Int) {
        val initialState = topicState
        topicExecutor.execute {
            val loadedState = ContactNoteFormWorkflow.loadTopics(applicationContext, initialState)
            runOnUiThread {
                if (generation != editorGeneration || isFinishing || isDestroyed || !topicState.visible) return@runOnUiThread
                topicState = when {
                    preferredCompanyId == ContactNoteTopicState.LOCAL_COMPANY_ID ->
                        loadedState.copy(selectedCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID)
                    preferredCompanyId.isNotBlank() && loadedState.companies.any { it.id == preferredCompanyId } ->
                        loadedState.copy(selectedCompanyId = preferredCompanyId)
                    preferredCompanyId.isNotBlank() && loadedState.loadError.isNotBlank() ->
                        loadedState.copy(selectedCompanyId = preferredCompanyId)
                    else -> loadedState
                }
                topicControl?.let(::bindTopicControl)
                noteInput?.let { scopeTextController?.refresh(topicState.selectedCompanyId, it) }
            }
        }
    }

    private fun bindTopicControl(control: RadioGroup) {
        ContactNoteTopicFieldUi(this, ::dp).bind(
            control = control,
            state = topicState,
            onSelected = { selected -> noteInput?.let { selectTopicCompany(selected, it) } },
            moveState = moveUiState(),
            onMoveAction = ::toggleMoveMode,
        )
    }

    private fun selectTopicCompany(selectedCompanyId: String, input: EditText) {
        if (moveMode) {
            moveToCompany(selectedCompanyId, input)
            return
        }
        val switched = ContactNoteScopeSwitchCoordinator.switch(
            currentCompanyId = topicState.selectedCompanyId,
            nextCompanyId = selectedCompanyId,
            editorReady = noteInput != null,
            persistCurrent = { saveForTransition(input.text?.toString().orEmpty()) },
            applyNext = { nextCompanyId ->
                preferredCompanyId = nextCompanyId
                topicState = topicState.copy(selectedCompanyId = nextCompanyId)
                scopeTextController?.refresh(nextCompanyId, input)
            },
        )
        if (!switched) {
            Toast.makeText(this, getString(R.string.dynamic_note_save_failed), Toast.LENGTH_SHORT).show()
            topicControl?.let(::bindTopicControl)
        }
    }

    private fun moveUiState(): ContactNoteMoveUiState {
        val selected = topicState.selectedCompanyId
        val text = noteInput?.text?.toString().orEmpty()
        return ContactNoteMoveUiState(
            canMove = !moveInProgress && ContactNoteMovePolicy.canStart(
                selectedCompanyId = selected,
                value = currentScopeValue,
                currentText = text,
                companyCount = topicState.companies.size,
            ),
            selectingTarget = moveMode,
            moving = moveInProgress,
            sourceCompanyId = moveSourceCompanyId,
        )
    }

    private fun toggleMoveMode() {
        if (moveInProgress) return
        if (moveMode) {
            cancelMoveMode()
            return
        }
        if (!moveUiState().canMove) return
        moveMode = true
        moveSourceCompanyId = topicState.selectedCompanyId
        topicControl?.let(::bindTopicControl)
    }

    private fun cancelMoveMode() {
        moveMode = false
        moveSourceCompanyId = ""
        topicControl?.let(::bindTopicControl)
    }

    private fun moveToCompany(targetCompanyId: String, input: EditText) {
        val sourceCompanyId = moveSourceCompanyId
        if (!ContactNoteMovePolicy.canTarget(sourceCompanyId, targetCompanyId)) {
            Toast.makeText(this, "Избери друга фирма", Toast.LENGTH_SHORT).show()
            topicControl?.let(::bindTopicControl)
            return
        }
        val sourceValue = currentScopeValue
        val targetValue = scopeTextController?.valueFor(targetCompanyId) ?: ContactNoteScopeValue()
        if (targetValue.text.isNotBlank() && !targetValue.confirmedServer) {
            Toast.makeText(this, "Изчакай бележката в целевата фирма да се синхронизира", Toast.LENGTH_SHORT).show()
            topicControl?.let(::bindTopicControl)
            return
        }
        val sourceText = input.text?.toString().orEmpty().trim()
        if (!sourceValue.confirmedServer || sourceValue.serverClientEventId.isBlank() || sourceText.isBlank()) {
            Toast.makeText(this, "Бележката още не е готова за преместване", Toast.LENGTH_SHORT).show()
            cancelMoveMode()
            return
        }

        moveInProgress = true
        topicControl?.let(::bindTopicControl)
        val request = ContactNoteMoveRequest(
            noteKind = if (isGeneralNote) "main" else "call",
            sourceCompanyId = sourceCompanyId,
            targetCompanyId = targetCompanyId,
            sourceClientEventId = sourceValue.serverClientEventId,
            targetClientEventId = targetValue.serverClientEventId.takeIf { targetValue.confirmedServer }.orEmpty(),
            phone = phone,
            sourceNote = sourceText,
        )
        topicExecutor.execute {
            val result = runCatching { ContactNoteMoveClient.move(applicationContext, request) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { moved ->
                    ContactNoteMoveClient.applyLocalResult(
                        applicationContext,
                        draft(),
                        sourceCompanyId,
                        sourceValue.serverClientEventId,
                        moved,
                    )
                    preferredCompanyId = moved.targetCompanyId
                    sendBroadcast(Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(packageName))
                    Toast.makeText(
                        this,
                        "Бележката е преместена в ${moved.targetCompanyName.ifBlank { companyName(moved.targetCompanyId) }}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }.onFailure { error ->
                    moveInProgress = false
                    moveMode = true
                    topicControl?.let(::bindTopicControl)
                    Toast.makeText(
                        this,
                        error.message.orEmpty().ifBlank { "Бележката не можа да бъде преместена" },
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun companyName(companyId: String): String =
        topicState.companies.firstOrNull { it.id == companyId }?.name.orEmpty().ifBlank { "другата фирма" }

    private fun saveAndSwitch(target: UnifiedNoteKind, noteText: String) {
        moveMode = false
        moveSourceCompanyId = ""
        if (target.isGeneral == isGeneralNote) return
        if (!saveForTransition(noteText)) {
            Toast.makeText(this, getString(R.string.dynamic_note_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        if (!target.isGeneral) resolveCallTargetForEditor()
        isGeneralNote = target.isGeneral
        serverClientEventId = currentServerEventId()
        topicState = initialTopicState()
        persistedEditorText = ""
        renderEditor()
    }

    private fun resolveCallTargetForEditor() {
        val target = CallNoteTargetResolver.resolve(
            context = applicationContext,
            phone = phone,
            directionHint = direction,
            callAtHint = callAt,
            durationHint = durationSeconds,
            actionIssuedAt = actionIssuedAt,
        )
        direction = target.direction
        callAt = target.callAt
        durationSeconds = target.durationSeconds
    }

    private fun saveAndClose(noteText: String) {
        val destination = selectedTopicCompanyIdOrNull() ?: return
        val outcome = saveController.save(noteText, destination)
        saveController.showOutcome(outcome)
        if (outcome.saved) {
            markCurrentTextPersisted(noteText)
            finish()
        }
    }

    private fun deleteSelectedNote() = saveAndClose("")

    private fun requestCloseWithoutSaving(noteText: String) {
        NoteEditorCloseConfirmation.request(
            activity = this,
            hasUnsavedChanges = noteText != persistedEditorText,
            closeWithoutSaving = ::finish,
        )
    }

    private fun saveForTransition(noteText: String): Boolean {
        if (noteText == persistedEditorText) return true
        val strictDestination = if (serverClientEventId.isNotBlank()) {
            topicState.selectedCompanyId.ifBlank { preferredCompanyId }
                .ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        } else ContactNoteFormWorkflow.selectedTopicOrLocalFallback(topicState)
        val fallbackLocally = strictDestination == null
        val destination = strictDestination ?: topicState.selectedCompanyId
            .ifBlank { preferredCompanyId }.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        val outcome = saveController.save(
            noteText,
            destination,
            topicState.loadError.isNotBlank() || fallbackLocally,
        )
        if (!outcome.saved) return false
        markCurrentTextPersisted(noteText)
        return true
    }

    private fun saveAndOpenCalendar(noteText: String) {
        val destination = selectedTopicCompanyIdOrNull() ?: return
        val outcome = saveController.save(noteText, destination)
        saveController.showOutcome(outcome)
        if (outcome.saved) {
            markCurrentTextPersisted(noteText)
            ContactNoteCalendarActions.open(
                this, titleText, phone, isGeneralNote, direction,
                callAt, durationSeconds, noteText,
            )
        }
    }

    private fun selectedTopicCompanyIdOrNull(): String? {
        if (serverClientEventId.isNotBlank()) return topicState.selectedCompanyId
            .ifBlank { preferredCompanyId }.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        return ContactNoteFormWorkflow.selectedTopicOrLocalFallback(topicState) ?: run {
            Toast.makeText(this, getString(R.string.note_company_required), Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun markCurrentTextPersisted(noteText: String) {
        persistedEditorText = noteText
        if (!isGeneralNote) initialNoteText = noteText
        storeCurrentServerEventId(serverClientEventId)
    }

    private fun initialTextForScope(): String = if (isGeneralNote) "" else initialNoteText

    private fun currentServerEventId(): String =
        if (isGeneralNote) generalServerClientEventId else callServerClientEventId

    private fun storeCurrentServerEventId(value: String) {
        if (isGeneralNote) generalServerClientEventId = value else callServerClientEventId = value
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val EXTRA_SHOW_NUMBER_KEYPAD = "show_number_keypad"
        const val EXTRA_NUMBER = "number"
    }
}
