package com.onlineimoti.calllog

import android.content.Context

/** Saves a note only under the user-selected company topic. */
internal object CallNoteTopicWriter {
    fun writeGeneral(
        context: Context,
        phone: String,
        text: String,
        companyId: String,
    ): CallNoteWriteResult {
        // A company/topic note is server-scoped metadata. It must not imply that
        // this device/user has marked the phone as a local CRM client.
        val previous = CallReportCompanyGeneralNoteStore.noteFor(context, phone, companyId)
        val cached = CallReportCompanyGeneralNoteStore.saveOrDelete(context, phone, companyId, text)
        if (!cached) return CallNoteWriteResult(false, true, CallNoteTarget("", 0L, 0L))

        val queued = CallReportTopicNoteOutbox.enqueueGeneral(context, phone, text, companyId)
        if (!CompanyMainNoteSavePolicy.isSaved(cached, queued)) {
            // Never report success for a server-scoped note that only reached the
            // temporary local cache. Restore the previous cache value instead.
            CallReportCompanyGeneralNoteStore.saveOrDelete(context, phone, companyId, previous)
            return CallNoteWriteResult(false, true, CallNoteTarget("", 0L, 0L))
        }

        HomeCrmCompanyMembershipStore.invalidate(context, phone)
        return CallNoteWriteResult(true, true, CallNoteTarget("", 0L, 0L))
    }

    fun writeCallOrGeneral(
        context: Context,
        phone: String,
        text: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        actionIssuedAt: Long,
        companyId: String,
        existingClientEventId: String = "",
        authorProfileId: String = "",
        authorName: String = "",
    ): CallNoteWriteResult {
        val target = targetFor(context, phone, direction, callAt, durationSeconds, actionIssuedAt)
        if (!target.hasCall) {
            return saveAsPendingCallNote(
                context = context,
                phone = phone,
                text = text,
                direction = direction,
                actionIssuedAt = actionIssuedAt,
                companyId = companyId,
                authorProfileId = authorProfileId,
                authorName = authorName,
            )
        }

        // Company call notes are independent server records. They deliberately do
        // not use the ordinary local-call-note slot, because that slot represents
        // only the Local note and would overwrite another company's note.
        val saved = CompanyCallNoteOutbox.enqueue(
            context = context,
            phone = phone,
            note = text,
            direction = target.direction,
            callAtMs = target.callAt,
            durationSeconds = target.durationSeconds,
            companyId = companyId,
            existingClientEventId = existingClientEventId,
            authorProfileId = authorProfileId,
            authorName = authorName,
        )
        val result = CallNoteWriteResult(saved, false, target)
        if (!saved) return result

        // The company note authorizes sharing this concrete call even when the
        // number is otherwise a known personal contact. It never opens future calls.
        CompanySharedCallStore.mark(context, phone, target.direction, target.callAt)
        CallReportSyncScheduler.enqueueCatchUp(
            context.applicationContext,
            reason = "company_call_note",
            initialDelayMillis = 500L,
        )
        PendingCallNoteStore.clearResolvedForCall(context, phone, target.direction, target.callAt)
        HomeCrmCompanyMembershipStore.invalidate(context, phone)
        return result
    }

    /**
     * A note opened as a call note must never turn into a company main note merely
     * because Android has not exposed the matching call-log row yet.
     */
    private fun saveAsPendingCallNote(
        context: Context,
        phone: String,
        text: String,
        direction: String,
        actionIssuedAt: Long,
        companyId: String,
        authorProfileId: String,
        authorName: String,
    ): CallNoteWriteResult {
        val activeSession = PendingCallNoteStore.activeSessionForPhone(context, phone)
        val shouldSavePending = activeSession != null || actionIssuedAt > 0L
        if (!shouldSavePending) {
            return CallNoteWriteResult(
                saved = false,
                savedAsGeneralNote = false,
                target = CallNoteTarget(direction, 0L, 0L),
            )
        }

        val pendingDirection = activeSession?.direction?.ifBlank { direction } ?: direction
        val pendingStartedAt = activeSession?.sessionStartedAt ?: actionIssuedAt
        val saved = PendingCallNoteStore.saveOrDelete(
            context = context,
            phone = phone,
            direction = pendingDirection,
            sessionStartedAt = pendingStartedAt,
            text = text,
            companyId = companyId,
            authorProfileId = authorProfileId,
            authorName = authorName,
        )
        if (saved) HomeCrmCompanyMembershipStore.invalidate(context, phone)
        if (saved && text.trim().isNotBlank() && activeSession == null) {
            PendingCallNoteStore.reconcileSoon(context, phone)
        }
        return CallNoteWriteResult(
            saved = saved,
            savedAsGeneralNote = false,
            target = CallNoteTarget(pendingDirection, 0L, 0L),
            savedAsPending = saved,
        )
    }

    private fun targetFor(
        context: Context,
        phone: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        actionIssuedAt: Long,
    ): CallNoteTarget {
        if (actionIssuedAt <= 0L && callAt > 0L) {
            return CallNoteTarget(direction, callAt, durationSeconds)
        }
        return CallNoteTargetResolver.resolve(context, phone, direction, callAt, durationSeconds, actionIssuedAt)
    }
}

internal object CompanyMainNoteSavePolicy {
    fun isSaved(cacheSaved: Boolean, queued: Boolean): Boolean = cacheSaved && queued
}
