package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal class HomeActions(
    private val activity: Activity,
    private val binding: ActivityHomeBinding,
    private val startTemporaryNoteRefresh: () -> Unit,
    private val isUnfilteredHome: () -> Boolean = { false },
) {
    fun openSettings() {
        activity.startActivity(Intent(activity, MainActivity::class.java))
    }

    fun openDialer(number: String) {
        if (number.isBlank()) return
        activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    fun openContactNotesScreen(phone: String, displayName: String) {
        if (phone.isBlank()) return
        activity.startActivity(
            Intent(activity, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, displayName.ifBlank { phone })
                .putExtra(ContactNotesActivity.EXTRA_BACK_TARGETS_UNFILTERED_HOME, isUnfilteredHome())
        )
    }

    fun openContactNotesScreen(call: PhoneCallRecord, displayName: String) {
        openContactNotesScreen(call.number, displayName)
    }

    fun openContactNotePopupForCall(
        call: PhoneCallRecord,
        displayName: String,
        renderedNote: HomeCallNote? = null,
    ) {
        if (renderedNote?.editable == false) {
            binding.homeStatusText.text = activity.getString(R.string.runtime_foreign_note_view_only)
            return
        }
        val existingNote = existingCallNote(call)
        val companyId = renderedNote?.companyId?.trim().takeUnless { it.isNullOrBlank() }
            ?: existingNote?.companyId.orEmpty().trim()
        val noteText = renderedNote?.text?.takeIf { it.isNotBlank() }
            ?: existingNote?.note.orEmpty()
        val serverClientEventId = renderedNote
            ?.takeIf { it.fromServer && it.editable }
            ?.serverClientEventId
            .orEmpty()

        openContactNoteEditorForCall(
            call = call,
            displayName = displayName,
            companyId = companyId,
            initialNoteText = noteText,
            serverClientEventId = serverClientEventId,
        )
    }

    private fun openContactNoteEditorForCall(
        call: PhoneCallRecord,
        displayName: String,
        companyId: String,
        initialNoteText: String,
        serverClientEventId: String,
    ) {
        activity.startActivity(
            CallNoteEditorLauncher.editorIntent(
                context = activity,
                mode = PostCallOverlayService.MODE_NOTE,
                phone = call.number,
                title = displayName,
                direction = call.direction,
                callAt = call.startedAt,
                durationSeconds = call.durationSeconds,
                companyId = companyId,
                initialNoteText = initialNoteText,
                serverClientEventId = serverClientEventId,
            )
        )
    }

    /** Returns the existing local note attached to this exact Call Log row. */
    private fun existingCallNote(call: PhoneCallRecord): ContactCallNote? {
        if (call.startedAt <= 0L) return null
        return ContactNoteReader.callNotesForPhone(activity.applicationContext, call.number)
            .asSequence()
            .filter { note ->
                note.callAt == call.startedAt &&
                    (call.direction.isBlank() || note.direction.isBlank() || note.direction == call.direction)
            }
            .maxByOrNull { note -> maxOf(note.savedAt, note.callAt) }
    }
}
