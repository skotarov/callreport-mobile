package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent

internal data class PostCallOverlayState(
    var formUrl: String = "",
    var phone: String = "",
    var direction: String = "",
    var title: String = "",
    var subtitle: String = "",
    var lines: List<String> = emptyList(),
    /** Currently selected company, carried while switching blue/yellow note forms. */
    var companyId: String = "",
    /** Original server note identity; when present, edits mutate that record in place. */
    var serverClientEventId: String = "",
    /** Initial text supplied by a Home server-note row before any local mirror exists. */
    var initialNoteText: String = "",
    /** True when the incoming-call coordinator already loads server history rows. */
    var remoteRowsArePreloaded: Boolean = false,
    /** One stable identity shared by all incremental updates for the active call. */
    var popupSessionId: String = "",
    var popupUpdateOnly: Boolean = false,
    var progressCalls: String = "",
    var progressLocalNotes: String = "",
    var progressServerNotes: String = "",
    var callAt: Long = 0L,
    var durationSeconds: Long = 0L,
    var actionIssuedAt: Long = 0L,
    var pendingGeneralNote: String? = null,
    var pendingCallNote: String? = null,
) {
    fun readExtras(intent: Intent?) {
        formUrl = intent?.getStringExtra(PostCallOverlayService.EXTRA_FORM_URL).orEmpty()
        phone = intent?.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty()
        direction = intent?.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty()
        title = intent?.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty()
        subtitle = intent?.getStringExtra(PostCallOverlayService.EXTRA_SUBTITLE).orEmpty()
        lines = intent?.getStringArrayListExtra(PostCallOverlayService.EXTRA_LINES).orEmpty()
        companyId = intent?.getStringExtra(CompanyMainNoteEditorLauncher.EXTRA_COMPANY_ID).orEmpty().trim()
        serverClientEventId = intent?.getStringExtra(CallNoteEditorLauncher.EXTRA_SERVER_CLIENT_EVENT_ID).orEmpty().trim()
        initialNoteText = intent?.getStringExtra(CallNoteEditorLauncher.EXTRA_INITIAL_NOTE_TEXT).orEmpty()
        remoteRowsArePreloaded = intent?.getBooleanExtra(PostCallOverlayService.EXTRA_REMOTE_ROWS_ARE_PRELOADED, false) ?: false
        popupSessionId = intent?.getStringExtra(PostCallOverlayService.EXTRA_POPUP_SESSION_ID).orEmpty()
        popupUpdateOnly = intent?.getBooleanExtra(PostCallOverlayService.EXTRA_POPUP_UPDATE_ONLY, false) ?: false
        progressCalls = intent?.getStringExtra(PostCallOverlayService.EXTRA_PROGRESS_CALLS).orEmpty()
        progressLocalNotes = intent?.getStringExtra(PostCallOverlayService.EXTRA_PROGRESS_LOCAL_NOTES).orEmpty()
        progressServerNotes = intent?.getStringExtra(PostCallOverlayService.EXTRA_PROGRESS_SERVER_NOTES).orEmpty()
        callAt = intent?.getLongExtra(PostCallOverlayService.EXTRA_CALL_AT, 0L) ?: 0L
        durationSeconds = intent?.getLongExtra(PostCallOverlayService.EXTRA_DURATION, 0L) ?: 0L
        actionIssuedAt = intent?.getLongExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, 0L) ?: 0L
    }

    fun hydrateLatestCallIfNeeded(context: Context) {
        val target = CallNoteTargetResolver.resolve(context, phone, direction, callAt, durationSeconds, actionIssuedAt)
        callAt = target.callAt
        durationSeconds = target.durationSeconds
        if (direction.isBlank()) direction = target.direction
    }

    fun clearPendingNotes() {
        pendingGeneralNote = null
        pendingCallNote = null
    }
}
