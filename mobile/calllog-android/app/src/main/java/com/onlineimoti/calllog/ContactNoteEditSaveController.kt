package com.onlineimoti.calllog

import android.content.Intent
import android.widget.Toast

internal data class ContactNoteEditSaveOutcome(
    val saved: Boolean,
    val serverSyncActivationAttempted: Boolean = false,
    val serverSyncEnabled: Boolean = false,
    val pendingServerSync: Boolean = false,
    val pendingCompanyChoice: Boolean = false,
    val companyName: String = "",
)

/** Keeps persistence and user feedback out of the editor Activity lifecycle code. */
internal class ContactNoteEditSaveController(
    private val activity: ContactNoteEditActivity,
    private val draft: () -> ContactNoteFormDraft,
    private val topicState: () -> ContactNoteTopicState,
    private val applyTarget: (CallNoteTarget) -> Unit,
) {
    fun save(
        noteText: String,
        topicCompanyId: String,
        localOnlyFallback: Boolean = topicState().loadError.isNotBlank(),
    ): ContactNoteEditSaveOutcome {
        val result = ContactNoteFormWorkflow.save(
            context = activity,
            draft = draft(),
            noteText = noteText,
            topicCompanyId = topicCompanyId,
            localOnlyFallback = localOnlyFallback,
        )
        if (!result.saved) return ContactNoteEditSaveOutcome(saved = false)
        if (!result.writeResult.savedAsGeneralNote) applyTarget(result.writeResult.target)

        // Home is normally paused while this editor is visible. Persist the change
        // before broadcasting so Home can still detect it after the receiver was absent.
        HomeNoteChangeSignal.markChanged(activity)
        activity.sendBroadcast(
            Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(activity.packageName),
        )
        return ContactNoteEditSaveOutcome(
            saved = true,
            serverSyncActivationAttempted = result.serverSyncActivationAttempted,
            serverSyncEnabled = result.serverSyncEnabled,
            pendingServerSync = result.pendingServerSync,
            pendingCompanyChoice = result.pendingCompanyChoice,
            companyName = companyNameFor(topicCompanyId),
        )
    }

    fun showOutcome(outcome: ContactNoteEditSaveOutcome) {
        val message = when {
            !outcome.saved -> activity.getString(R.string.dynamic_note_save_failed)
            outcome.pendingCompanyChoice -> activity.getString(R.string.dynamic_note_saved_choose_company_later)
            outcome.pendingServerSync -> activity.getString(
                R.string.dynamic_note_saved_pending_company_sync,
                outcome.companyName,
            )
            outcome.serverSyncActivationAttempted && outcome.serverSyncEnabled -> {
                activity.getString(R.string.note_server_sync_enabled)
            }
            outcome.serverSyncActivationAttempted -> activity.getString(R.string.note_server_sync_activation_failed)
            else -> activity.getString(R.string.dynamic_note_saved)
        }
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun companyNameFor(companyId: String): String {
        if (companyId == ContactNoteTopicState.LOCAL_COMPANY_ID) return ""
        return topicState().companies.firstOrNull { it.id == companyId }
            ?.name.orEmpty().ifBlank { companyId }
    }
}
