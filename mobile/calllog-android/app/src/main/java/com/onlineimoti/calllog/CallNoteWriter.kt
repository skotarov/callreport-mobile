package com.onlineimoti.calllog

import android.content.Context

internal data class CallNoteWriteResult(
    val saved: Boolean,
    val savedAsGeneralNote: Boolean,
    val target: CallNoteTarget,
    val savedAsPending: Boolean = false,
)

internal object CallNoteWriter {
    fun writeGeneral(
        context: Context,
        phone: String,
        text: String,
        syncToCrm: Boolean = true,
    ): CallNoteWriteResult {
        val saved = NotePersistence.saveOrDeleteGeneralNote(context, phone, text)
        val result = CallNoteWriteResult(saved, true, CallNoteTarget("", 0L, 0L))
        if (syncToCrm) syncToCrmIfNeeded(context, phone, text, result)
        return result
    }

    fun writeCallOrGeneral(
        context: Context,
        phone: String,
        text: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        actionIssuedAt: Long = 0L,
        syncToCrm: Boolean = true,
    ): CallNoteWriteResult {
        val target = targetFor(context, phone, direction, callAt, durationSeconds, actionIssuedAt)
        if (!target.hasCall) {
            val activeSession = PendingCallNoteStore.activeSessionForPhone(context, phone)
            val shouldSavePending = activeSession != null || actionIssuedAt > 0L
            if (shouldSavePending) {
                val pendingDirection = activeSession?.direction?.ifBlank { direction } ?: direction
                val pendingStartedAt = activeSession?.sessionStartedAt ?: actionIssuedAt
                val saved = PendingCallNoteStore.saveOrDelete(context, phone, pendingDirection, pendingStartedAt, text)
                if (saved && text.trim().isNotBlank() && activeSession == null) PendingCallNoteStore.reconcileSoon(context, phone)
                return CallNoteWriteResult(saved, false, CallNoteTarget(pendingDirection, 0L, 0L), savedAsPending = true)
            }
            // The yellow/main and blue/call tabs are independent editor modes.
            // ContactNoteFormWorkflow deliberately calls this writer with
            // syncToCrm=false. In that explicit blue mode, a missing call target
            // must never silently turn the blue text into the yellow main note.
            // Legacy direct callers that still request CRM sync keep the historical
            // call-or-general fallback for backward compatibility.
            if (!syncToCrm) {
                return CallNoteWriteResult(
                    saved = false,
                    savedAsGeneralNote = false,
                    target = CallNoteTarget(direction, 0L, 0L),
                )
            }
            return writeGeneral(context, phone, text, syncToCrm)
        }
        val saved = NotePersistence.saveOrDeleteCallNote(
            context = context,
            phoneNumber = phone,
            note = text,
            direction = target.direction,
            callAt = target.callAt,
            durationSeconds = target.durationSeconds,
        )
        val result = CallNoteWriteResult(saved, false, target)
        if (saved) PendingCallNoteStore.clearResolvedForCall(context, phone, target.direction, target.callAt)
        if (syncToCrm) syncToCrmIfNeeded(context, phone, text, result)
        return result
    }

    private fun syncToCrmIfNeeded(context: Context, phone: String, text: String, result: CallNoteWriteResult) {
        if (!result.saved) return
        if (result.savedAsGeneralNote) {
            RmLayerContactDataSyncer.sync(context, phone, noteOverride = text)
            // A blank value is a real delete operation and must reach the durable server outbox.
            CrmNoteSyncer.syncGeneralIfEnabled(context, phone, text)
        } else if (result.target.hasCall) {
            RmLayerContactDataSyncer.sync(context, phone)
            val clientNoteId = LocalNotesFileStore.clientNoteIdForCall(phone, result.target.callAt, result.target.direction)
            // A blank value clears the note for the same stable call-note id on the server.
            CrmNoteSyncer.syncCallIfEnabled(
                context = context,
                phone = phone,
                note = text,
                direction = result.target.direction,
                callAt = result.target.callAt,
                durationSeconds = result.target.durationSeconds,
                clientNoteId = clientNoteId,
            )
        }
    }

    private fun targetFor(
        context: Context,
        phone: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        actionIssuedAt: Long,
    ): CallNoteTarget {
        if (actionIssuedAt <= 0L && callAt > 0L) return CallNoteTarget(direction, callAt, durationSeconds)
        return CallNoteTargetResolver.resolve(context, phone, direction, callAt, durationSeconds, actionIssuedAt)
    }
}
