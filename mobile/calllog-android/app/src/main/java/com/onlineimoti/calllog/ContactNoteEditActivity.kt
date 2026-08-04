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
        saveAndCloseIfChanged(noteInput?.text?.toString().orEmpty())
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
            initialValue = { ContactNoteScopeValue(initialTextForScope(), serverClientEventId) },
            onValueApplied = { _, value ->
                persistedEditorText = value.text
                serverClientEventId = value.serverClientEventId
                storeCurrentServerEventId(value.serverClientEventId)
                if (!isGeneralNote) initialNoteText = value.text
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
            saveAndSwitch = ::saveAndSwitch,
            saveAndClose = ::saveAndClose,
            deleteAndClose = ::deleteSelectedNote,
            saveAndOpenCalendar = ::saveAndOpenCalendar,
            close = ::saveAndCloseIfChanged,
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
        ContactNoteTopicFieldUi(this, ::dp).bind(control, topicState) { selected ->
            noteInput?.let { selectTopicCompany(selected, it) }
        }
    }

    private fun selectTopicCompany(selectedCompanyId: String, input: EditText) {
        val switched = ContactNoteScopeSwitchCoordinator.switch(
            currentCompanyId = topicState.selectedCompanyId,
            nextCompanyId = selectedCompanyId,
            editorReady = noteInput != null,
            persistCurrent = { saveForTransition(input.text?.toString().orEmpty()) },
            applyNext = { nextCompanyId ->
                topicState = topicState.copy(selectedCompanyId = nextCompanyId)
                scopeTextController?.refresh(nextCompanyId, input)
            },
        )
        if (!switched) {
            Toast.makeText(this, getString(R.string.dynamic_note_save_failed), Toast.LENGTH_SHORT).show()
            topicControl?.let(::bindTopicControl)
        }
    }

    private fun saveAndSwitch(target: UnifiedNoteKind, noteText: String) {
        if (target.isGeneral == isGeneralNote) return
        if (!saveForTransition(noteText)) {
            Toast.makeText(this, getString(R.string.dynamic_note_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        isGeneralNote = target.isGeneral
        serverClientEventId = currentServerEventId()
        topicState = initialTopicState()
        persistedEditorText = ""
        renderEditor()
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

    private fun saveAndCloseIfChanged(noteText: String) {
        if (!saveForTransition(noteText)) {
            Toast.makeText(this, getString(R.string.dynamic_note_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        finish()
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
