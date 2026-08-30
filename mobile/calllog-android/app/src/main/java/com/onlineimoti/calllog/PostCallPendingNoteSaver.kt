package com.onlineimoti.calllog

import android.content.Context

/** Persists unsaved overlay note text before navigating away from the call popup. */
internal object PostCallPendingNoteSaver {
    fun save(context: Context, state: PostCallOverlayState, onSaved: () -> Unit): Boolean {
        var saved = true
        state.pendingGeneralNote?.let { note ->
            saved = CallNoteWriter.writeGeneral(context, state.phone, note).saved && saved
        }
        state.pendingCallNote?.let { note ->
            saved = CallNoteWriter.writeCallOrGeneral(
                context = context,
                phone = state.phone,
                text = note,
                direction = state.direction,
                callAt = state.callAt,
                durationSeconds = state.durationSeconds,
                actionIssuedAt = state.actionIssuedAt,
                // This is an explicitly blue/call note. While the system call-log
                // row is still delayed, preserve it as pending instead of turning it
                // into the yellow general note.
                syncToCrm = false,
            ).saved && saved
        }
        if (saved) {
            state.clearPendingNotes()
            onSaved()
        }
        return saved
    }
}
