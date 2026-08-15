package com.onlineimoti.calllog

import android.content.Context

/**
 * Shared business flow for every note editor, whether it is full-screen or an
 * overlay popup. UI implementations only render [ContactNoteTopicState].
 */
internal data class ContactNoteFormDraft(
    val phone: String,
    val title: String,
    val direction: String = "",
    val callAt: Long = 0L,
    val durationSeconds: Long = 0L,
    val actionIssuedAt: Long = 0L,
    val isGeneralNote: Boolean = false,
    /** Original cloud note id means the editor must mutate that existing note. */
    val serverClientEventId: String = "",
)

internal data class ContactNoteFormSaveResult(
    val writeResult: CallNoteWriteResult,
    val localOnlyFallback: Boolean = false,
    val serverSyncActivationAttempted: Boolean = false,
    val serverSyncEnabled: Boolean = false,
    val pendingServerSync: Boolean = false,
    val pendingCompanyChoice: Boolean = false,
) {
    val saved: Boolean get() = writeResult.saved
}

internal object ContactNoteFormWorkflow {
    fun initialTopicState(context: Context, draft: ContactNoteFormDraft): ContactNoteTopicState {
        val visible = shouldShowTopicSelector(context, draft)
        // A CRM contact and an unknown number can choose Local or a real firm.
        // A normal known contact without CRM remains strictly local.
        val localOnly = visible && !ContactServerCompanyScope.isAvailable(context, draft.phone)
        return ContactNoteTopicState(
            visible = visible,
            loading = visible && !localOnly,
            selectedCompanyId = if (localOnly) ContactNoteTopicState.LOCAL_COMPANY_ID else "",
            includeLocalOption = visible,
            localOnly = localOnly,
        )
    }

    fun loadTopics(context: Context, previous: ContactNoteTopicState): ContactNoteTopicState {
        if (!previous.visible) return previous
        if (previous.localOnly) {
            return previous.copy(
                loading = false,
                companies = emptyList(),
                selectedCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID,
                loadError = "",
                usingCachedCompanies = false,
                cachedCompaniesUpdatedAtMs = 0L,
            )
        }

        val result = runCatching {
            CallReportTopicCompaniesRepository.load(
                context = context.applicationContext,
                config = ConfigStore.load(context.applicationContext),
            )
        }
        val loaded = result.getOrNull()
        val companies = loaded?.companies.orEmpty()
        val loadFailed = result.isFailure
        val selectedCompanyId = when {
            previous.selectedCompanyId == ContactNoteTopicState.LOCAL_COMPANY_ID -> {
                ContactNoteTopicState.LOCAL_COMPANY_ID
            }
            loadFailed -> ""
            else -> previous.selectedCompanyId.takeIf { selected -> companies.any { it.id == selected } }.orEmpty()
        }
        return previous.copy(
            loading = false,
            companies = companies,
            selectedCompanyId = selectedCompanyId,
            loadError = if (loadFailed) TOPIC_REQUEST_FAILED else "",
            usingCachedCompanies = loaded?.source == TopicCompaniesSource.CACHED,
            cachedCompaniesUpdatedAtMs = if (loaded?.source == TopicCompaniesSource.CACHED) loaded.updatedAtMs else 0L,
        )
    }

    /** Returns null while an eligible CRM/unknown contact still needs a destination selection. */
    fun selectedTopicOrLocalFallback(state: ContactNoteTopicState): String? {
        if (!state.visible || state.localOnly) return ContactNoteTopicState.LOCAL_COMPANY_ID
        // Explicit Local is a complete destination even while the server company
        // list is still loading. Blocking it made the History main-note Local card
        // look as if it did not save unless a remote/company destination was used.
        if (state.selectedCompanyId == ContactNoteTopicState.LOCAL_COMPANY_ID) return ContactNoteTopicState.LOCAL_COMPANY_ID
        // Without a cached list, save locally and retain a durable reminder to choose
        // a firm later. The user never loses the note because of a connection error.
        if (state.loadError.isNotBlank()) return ContactNoteTopicState.LOCAL_COMPANY_ID
        if (state.loading || state.selectedCompanyId.isBlank()) return null
        return state.selectedCompanyId
    }

    fun willEnableServerSync(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") draft: ContactNoteFormDraft,
        @Suppress("UNUSED_PARAMETER") state: ContactNoteTopicState,
    ): Boolean {
        // Company-scoped notes are allowed to sync as notes, but selecting a firm
        // must never silently switch the phone into the user's local CRM list.
        return false
    }

    fun save(
        context: Context,
        draft: ContactNoteFormDraft,
        noteText: String,
        topicCompanyId: String,
        localOnlyFallback: Boolean = false,
        scheduleServerSync: Boolean = true,
    ): ContactNoteFormSaveResult {
        val appContext = context.applicationContext
        val existingServerNoteId = draft.serverClientEventId.trim()
            .takeUnless { ContactNoteMovePolicy.isLocalEventId(it) }
            .orEmpty()

        // A stale UI or a direct caller cannot send an ordinary known non-CRM
        // contact to the server.
        val serverDestinationAllowed = ContactServerCompanyScope.isAvailable(appContext, draft.phone)
        val selectedTopic = topicCompanyId.trim()
        val isLocalSelection = selectedTopic == ContactNoteTopicState.LOCAL_COMPANY_ID || !serverDestinationAllowed
        val serverCompanyId = if (isLocalSelection) "" else selectedTopic

        val writeResult = when {
            // A server-only unscoped note still uses the legacy in-place edit path.
            existingServerNoteId.isNotBlank() && serverCompanyId.isBlank() && !draft.isGeneralNote -> {
                val queued = CallReportNoteOutbox.enqueueExistingServerNote(
                    context = appContext,
                    phone = draft.phone,
                    note = noteText,
                    serverClientEventId = existingServerNoteId,
                    direction = draft.direction,
                    callAt = draft.callAt,
                    durationSeconds = draft.durationSeconds,
                )
                CallNoteWriteResult(
                    saved = queued,
                    savedAsGeneralNote = false,
                    target = CallNoteTarget(draft.direction, draft.callAt, draft.durationSeconds),
                )
            }
            serverCompanyId.isNotBlank() && draft.isGeneralNote -> {
                CallNoteTopicWriter.writeGeneral(
                    context = appContext,
                    phone = draft.phone,
                    text = noteText,
                    companyId = serverCompanyId,
                    scheduleWorker = scheduleServerSync,
                )
            }
            serverCompanyId.isNotBlank() -> {
                CallNoteTopicWriter.writeCallOrGeneral(
                    context = appContext,
                    phone = draft.phone,
                    text = noteText,
                    direction = draft.direction,
                    callAt = draft.callAt,
                    durationSeconds = draft.durationSeconds,
                    actionIssuedAt = draft.actionIssuedAt,
                    companyId = serverCompanyId,
                    existingClientEventId = existingServerNoteId,
                    scheduleWorker = scheduleServerSync,
                )
            }
            draft.isGeneralNote -> {
                CallNoteWriter.writeGeneral(
                    context = appContext,
                    phone = draft.phone,
                    text = noteText,
                    syncToCrm = false,
                )
            }
            else -> CallNoteWriter.writeCallOrGeneral(
                context = appContext,
                phone = draft.phone,
                text = noteText,
                direction = draft.direction,
                callAt = draft.callAt,
                durationSeconds = draft.durationSeconds,
                actionIssuedAt = draft.actionIssuedAt,
                syncToCrm = false,
            )
        }
        if (!writeResult.saved) {
            return ContactNoteFormSaveResult(
                writeResult = writeResult,
                localOnlyFallback = localOnlyFallback,
            )
        }

        // Deletion is represented by an absent/blank row, so there is no new value
        // that can overwrite Home's durable snapshot. Invalidate it in this shared
        // workflow so full-screen, overlay and future editors all behave identically.
        if (noteText.isBlank()) {
            val target = writeResult.target
            HomeNotesSnapshotCache.invalidateDeletedNote(
                context = appContext,
                phone = draft.phone,
                isGeneralNote = writeResult.savedAsGeneralNote || draft.isGeneralNote,
                callAtMs = target.callAt.takeIf { it > 0L } ?: draft.callAt,
                direction = target.direction.ifBlank { draft.direction },
                serverClientEventId = existingServerNoteId,
            )
        }

        val pendingCompanyChoice = localOnlyFallback &&
            isLocalSelection &&
            serverDestinationAllowed &&
            noteText.trim().isNotBlank()
        updateDeferredCompanyAssignment(
            context = appContext,
            draft = draft,
            writeResult = writeResult,
            pendingCompanyChoice = pendingCompanyChoice,
        )

        // Local, Firm A, Firm B, etc. are independent note scopes. Selecting Local
        // must not unassign or delete the notes already stored for other firms.
        val pendingServerSync = when {
            existingServerNoteId.isNotBlank() && serverCompanyId.isBlank() -> true
            serverCompanyId.isNotBlank() -> isTopicSyncPending(
                context = appContext,
                phone = draft.phone,
                writeResult = writeResult,
                companyId = serverCompanyId,
            )
            else -> false
        }
        return ContactNoteFormSaveResult(
            writeResult = writeResult,
            localOnlyFallback = localOnlyFallback,
            pendingServerSync = pendingServerSync,
            pendingCompanyChoice = pendingCompanyChoice,
        )
    }

    private fun updateDeferredCompanyAssignment(
        context: Context,
        draft: ContactNoteFormDraft,
        writeResult: CallNoteWriteResult,
        pendingCompanyChoice: Boolean,
    ) {
        if (writeResult.savedAsGeneralNote) {
            if (pendingCompanyChoice) {
                CallReportDeferredCompanyAssignmentStore.markGeneral(context, draft.phone)
            } else {
                CallReportDeferredCompanyAssignmentStore.clearGeneral(context, draft.phone)
            }
            return
        }

        if (!writeResult.target.hasCall) return
        if (pendingCompanyChoice) {
            CallReportDeferredCompanyAssignmentStore.markCall(
                context = context,
                phone = draft.phone,
                direction = writeResult.target.direction,
                callAtMs = writeResult.target.callAt,
                durationSeconds = writeResult.target.durationSeconds,
            )
        } else {
            CallReportDeferredCompanyAssignmentStore.clearCall(
                context = context,
                phone = draft.phone,
                direction = writeResult.target.direction,
                callAtMs = writeResult.target.callAt,
            )
        }
    }

    private fun isTopicSyncPending(
        context: Context,
        phone: String,
        writeResult: CallNoteWriteResult,
        companyId: String,
    ): Boolean {
        return if (writeResult.savedAsGeneralNote) {
            CallReportTopicNoteOutbox.isGeneralPending(context, phone, companyId)
        } else if (writeResult.target.hasCall) {
            CompanyCallNoteOutbox.isCallPending(
                context = context,
                phone = phone,
                direction = writeResult.target.direction,
                callAtMs = writeResult.target.callAt,
            )
        } else {
            false
        }
    }

    private fun shouldShowTopicSelector(context: Context, @Suppress("UNUSED_PARAMETER") draft: ContactNoteFormDraft): Boolean {
        return CallReportRemoteAccess.isReady(ConfigStore.load(context.applicationContext))
    }

    private const val TOPIC_REQUEST_FAILED = "topic_request_failed"
}
