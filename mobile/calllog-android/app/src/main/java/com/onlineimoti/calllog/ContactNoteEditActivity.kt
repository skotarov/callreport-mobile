package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
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
    private var launchedAsGeneralNote = false
    private var preferredCompanyId = ""
    private var initialNoteText = ""
    private var initialServerClientEventId = ""
    private var callServerClientEventId = ""
    private var generalServerClientEventId = ""
    private var serverClientEventId = ""
    private var topicState = ContactNoteTopicState(visible = false)
    private var fieldsContainer: LinearLayout? = null
    private var focusedScopeId = ContactNoteTopicState.LOCAL_COMPANY_ID
    private var editorGeneration = 0
    private val scopeInputs = linkedMapOf<String, EditText>()
    private val scopeTexts = linkedMapOf<String, String>()
    private val persistedScopeValues = linkedMapOf<String, ContactNoteScopeValue>()
    private var serverScopeValues: Map<String, ContactNoteScopeValue>? = null
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
        requestCloseWithoutSaving("")
    }

    override fun onDestroy() {
        editorGeneration += 1
        topicExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun renderEditor() {
        editorGeneration += 1
        val generation = editorGeneration
        fieldsContainer = null
        scopeInputs.clear()
        scopeTexts.clear()
        persistedScopeValues.clear()
        serverScopeValues = null
        seedInitialValues()

        setContentView(ContactNoteEditUi(
            activity = this,
            state = ::uiState,
            fieldStateForScope = ::fieldStateForScope,
            onScopeInputReady = ::onScopeInputReady,
            onFieldsReady = { fieldsContainer = it },
            saveAndSwitch = ::saveAndSwitch,
            saveAndClose = ::saveAndClose,
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
        launchedAsGeneralNote = isGeneralNote
        preferredCompanyId = intent.getStringExtra(CompanyMainNoteEditorLauncher.EXTRA_COMPANY_ID).orEmpty().trim()
        // A company main note has already been loaded on History. Retain that
        // authoritative value so the first editor render is populated instead of
        // briefly looking like an empty new note while the background refresh runs.
        initialNoteText = intent.getStringExtra(CallNoteEditorLauncher.EXTRA_INITIAL_NOTE_TEXT).orEmpty()
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
            !isGeneralNote && initialValueBelongsToCurrentKind() && initialNoteText.isNotBlank() && base.visible && !base.localOnly ->
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
        initialNoteText = initialNoteText,
    )

    private fun seedInitialValues() {
        val localId = ContactNoteTopicState.LOCAL_COMPANY_ID
        val localValue = ContactNoteScopeTextResolver.cachedValue(this, draft(), localId)
        persistedScopeValues[localId] = localValue
        scopeTexts[localId] = localValue.text

        val initialTextForCurrentKind = initialNoteText.takeIf { initialValueBelongsToCurrentKind() }.orEmpty()
        val initialServerIdForCurrentKind = serverClientEventId.takeIf { initialValueBelongsToCurrentKind() }.orEmpty()

        val initialScopeId = preferredCompanyId
            .ifBlank { topicState.selectedCompanyId }
            .ifBlank { localId }
        if (initialScopeId != localId && (initialTextForCurrentKind.isNotBlank() || initialServerIdForCurrentKind.isNotBlank())) {
            val initialValue = ContactNoteScopeValue(
                text = initialTextForCurrentKind,
                serverClientEventId = initialServerIdForCurrentKind,
                confirmedServer = initialServerIdForCurrentKind.isNotBlank() && ServerRecordIndex.isConfirmed(this, initialServerIdForCurrentKind),
            )
            persistedScopeValues[initialScopeId] = initialValue
            scopeTexts[initialScopeId] = initialValue.text
        } else if (initialScopeId == localId && initialTextForCurrentKind.isNotBlank() && localValue.text.isBlank()) {
            val initialValue = localValue.copy(text = initialTextForCurrentKind)
            persistedScopeValues[localId] = initialValue
            scopeTexts[localId] = initialValue.text
        }
    }

    private fun initialValueBelongsToCurrentKind(): Boolean =
        ContactNoteInitialValuePolicy.belongsToCurrentKind(launchedAsGeneralNote, isGeneralNote)

    private fun loadTopicCompanies(generation: Int) {
        val initialState = topicState
        topicExecutor.execute {
            val loadedState = ContactNoteFormWorkflow.loadTopics(applicationContext, initialState)
            val loadedServerValues = if (isGeneralNote || loadedState.companies.isNotEmpty()) {
                runCatching { ContactNoteScopeTextResolver.loadServerValues(applicationContext, draft()) }.getOrNull()
            } else null
            runOnUiThread {
                if (generation != editorGeneration || isFinishing || isDestroyed) return@runOnUiThread
                captureScopeTexts()
                val previousState = topicState
                topicState = when {
                    preferredCompanyId == ContactNoteTopicState.LOCAL_COMPANY_ID ->
                        loadedState.copy(selectedCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID)
                    preferredCompanyId.isNotBlank() && loadedState.companies.any { it.id == preferredCompanyId } ->
                        loadedState.copy(selectedCompanyId = preferredCompanyId)
                    else -> loadedState
                }
                serverScopeValues = loadedServerValues
                var scopeValuesChanged = false
                topicState.companies.forEach { company ->
                    val value = ContactNoteScopeTextResolver.valueFor(
                        companyId = company.id,
                        draft = draft(),
                        serverValues = loadedServerValues,
                        context = this,
                    )
                    // Preserve text the user is actively editing. Otherwise reconcile
                    // the field baseline with the refreshed server/cached value.
                    val previousValue = persistedScopeValues[company.id]
                    val currentText = scopeTexts[company.id]
                    val canApply = previousValue == null || currentText == previousValue.text
                    if (canApply) {
                        if (currentText != null && currentText != value.text) scopeValuesChanged = true
                        persistedScopeValues[company.id] = value
                        scopeTexts[company.id] = value.text
                    }
                }
                if (ContactNoteTopicRenderPolicy.shouldRebind(previousState, topicState, scopeValuesChanged)) {
                    bindAllFields()
                }
            }
        }
    }

    private fun bindAllFields() {
        val container = fieldsContainer ?: return
        captureScopeTexts()
        scopeInputs.clear()
        ContactNoteMultiScopeFieldsUi(this, ::dp).bind(
            container = container,
            state = topicState,
            kind = if (isGeneralNote) UnifiedNoteKind.GENERAL else UnifiedNoteKind.CALL,
            fieldStateFor = ::fieldStateForScope,
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

    private fun textForScope(companyId: String): String {
        scopeTexts[companyId]?.let { return it }
        val value = persistedScopeValues[companyId] ?: ContactNoteScopeTextResolver.valueFor(
            companyId = companyId,
            draft = draft(),
            serverValues = serverScopeValues,
            context = this,
        ).also { persistedScopeValues[companyId] = it }
        return value.text
    }

    private fun fieldStateForScope(companyId: String): ContactNoteScopeFieldUiState =
        persistedScopeValues.containsKey(companyId).let { hadPersistedValue ->
            ContactNoteScopeFieldLoadPolicy.resolve(
                companyId = companyId,
                topicState = topicState,
                text = textForScope(companyId),
                hasPersistedValue = hadPersistedValue,
            )
        }

    private fun captureScopeTexts() {
        scopeInputs.forEach { (companyId, input) ->
            scopeTexts[companyId] = input.text?.toString().orEmpty()
        }
    }

    private fun scopeIds(): List<String> = buildList {
        add(ContactNoteTopicState.LOCAL_COMPANY_ID)
        topicState.companies.mapTo(this) { it.id }
    }.filter { it.isNotBlank() }.distinct()

    private fun hasUnsavedChanges(): Boolean {
        captureScopeTexts()
        return scopeIds().any { id ->
            scopeTexts[id].orEmpty() != persistedScopeValues[id]?.text.orEmpty()
        }
    }

    private fun saveAll(showOutcome: Boolean): Boolean {
        captureScopeTexts()
        val originalEventId = serverClientEventId
        var lastOutcome: ContactNoteEditSaveOutcome? = null
        var queuedCompanySync = false
        for (companyId in scopeIds()) {
            val text = scopeTexts[companyId].orEmpty()
            val persisted = persistedScopeValues[companyId] ?: ContactNoteScopeValue()
            if (text == persisted.text) continue

            // Each company has its own server identity. Never reuse one company's
            // client_event_id while saving another field.
            serverClientEventId = persisted.serverClientEventId
            val isCompanyScope = companyId != ContactNoteTopicState.LOCAL_COMPANY_ID
            val outcome = saveController.save(
                noteText = text,
                topicCompanyId = companyId,
                localOnlyFallback = false,
                scheduleServerSync = !isCompanyScope,
            )
            if (!outcome.saved) {
                serverClientEventId = originalEventId
                if (queuedCompanySync) scheduleQueuedCompanySync()
                if (showOutcome) saveController.showOutcome(outcome)
                return false
            }
            if (isCompanyScope) queuedCompanySync = true
            persistedScopeValues[companyId] = persisted.copy(text = text)
            lastOutcome = outcome
        }
        serverClientEventId = originalEventId
        if (queuedCompanySync) scheduleQueuedCompanySync()
        if (showOutcome) {
            saveController.showOutcome(lastOutcome ?: ContactNoteEditSaveOutcome(saved = true))
        }
        return true
    }

    private fun scheduleQueuedCompanySync() {
        if (isGeneralNote) {
            CallReportTopicNoteOutbox.requestSyncNow(applicationContext)
        } else {
            CompanyCallNoteOutbox.requestSyncNow(applicationContext)
            CallReportSyncScheduler.enqueueCatchUp(
                applicationContext,
                reason = "company_call_note_batch",
                initialDelayMillis = 500L,
            )
        }
    }

    private fun saveAndSwitch(target: UnifiedNoteKind, @Suppress("UNUSED_PARAMETER") ignoredText: String) {
        if (target.isGeneral == isGeneralNote) return
        if (!saveAll(showOutcome = false)) {
            Toast.makeText(this, getString(R.string.dynamic_note_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        if (!target.isGeneral) resolveCallTargetForEditor()
        isGeneralNote = target.isGeneral
        serverClientEventId = currentServerEventId()
        topicState = initialTopicState()
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

    private fun saveAndClose(@Suppress("UNUSED_PARAMETER") ignoredText: String) {
        if (saveAll(showOutcome = true)) finish()
    }

    private fun requestCloseWithoutSaving(@Suppress("UNUSED_PARAMETER") ignoredText: String) {
        NoteEditorCloseConfirmation.request(
            activity = this,
            hasUnsavedChanges = hasUnsavedChanges(),
            closeWithoutSaving = ::finish,
        )
    }

    private fun saveAndOpenCalendar(@Suppress("UNUSED_PARAMETER") ignoredText: String) {
        if (!saveAll(showOutcome = true)) return
        ContactNoteCalendarActions.open(
            this,
            titleText,
            phone,
            isGeneralNote,
            direction,
            callAt,
            durationSeconds,
            calendarGeneralNotes(),
            currentCallNoteForCalendar(),
        )
    }

    private fun calendarGeneralNotes(): List<String> {
        captureScopeTexts()
        val stored = ContactNoteCalendarContent.storedGeneralNotes(
            context = applicationContext,
            phone = phone,
            companyIds = topicState.companies.map { it.id },
        )
        return (if (isGeneralNote) scopeIds().map { scopeTexts[it].orEmpty() } else emptyList()) + stored
    }

    /** Only a Call editor owns a blue note; a General editor must not inspect History for one. */
    private fun currentCallNoteForCalendar(): String? = if (isGeneralNote) null else focusedText()

    private fun focusedText(): String {
        captureScopeTexts()
        return scopeTexts[focusedScopeId]
            ?: scopeTexts[ContactNoteTopicState.LOCAL_COMPANY_ID]
            .orEmpty()
    }

    private fun currentServerEventId(): String =
        if (isGeneralNote) generalServerClientEventId else callServerClientEventId

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val EXTRA_SHOW_NUMBER_KEYPAD = "show_number_keypad"
        const val EXTRA_NUMBER = "number"
    }
}
