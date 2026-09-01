package com.onlineimoti.calllog

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

class PostCallOverlayService : FontScaledService() {
    private val handler = Handler(Looper.getMainLooper())
    private val state = PostCallOverlayState()
    private val ui: PostCallOverlayUi by lazy { PostCallOverlayUi(this) }
    private val noteEditor: PostCallUnifiedNoteEditor by lazy {
        PostCallUnifiedNoteEditor(
            service = this,
            ui = ui,
            handler = handler,
            phone = { state.phone },
            direction = { state.direction },
            callAt = { state.callAt },
            durationSeconds = { state.durationSeconds },
            actionIssuedAt = { state.actionIssuedAt },
            preferredCompanyId = { state.companyId },
            setPreferredCompanyId = { state.companyId = it },
            initialNoteText = { state.initialNoteText },
            serverClientEventId = { state.serverClientEventId },
            setWindowManager = { windowManager = it },
            removeOverlay = ::removeOverlay,
            addDraggableOverlay = ::addDraggableOverlay,
            openCalendarEvent = ::openCalendarEvent,
            openContactNotesScreen = ::openContactNotesScreen,
            pendingCallNote = { state.pendingCallNote },
            setPendingCallNote = { state.pendingCallNote = it },
            pendingGeneralNote = { state.pendingGeneralNote },
            setPendingGeneralNote = { state.pendingGeneralNote = it },
            notifyNotesChanged = ::notifyNotesChanged,
            closeEditor = ::closeNoteEditor,
        )
    }
    private val lookupPopup: PostCallLookupPopup by lazy {
        PostCallLookupPopup(
            service = this,
            ui = ui,
            phone = { state.phone },
            title = { state.title },
            lookupLines = { state.lines },
            remoteRowsArePreloaded = { state.remoteRowsArePreloaded },
            popupSessionId = { state.popupSessionId },
            popupUpdateOnly = { state.popupUpdateOnly },
            progressiveRows = {
                IncomingCallPopupProgress(
                    calls = IncomingCallPopupStatusText.compact(state.progressCalls),
                    localNotes = IncomingCallPopupStatusText.compact(state.progressLocalNotes),
                    serverNotes = IncomingCallPopupStatusText.compact(state.progressServerNotes),
                )
            },
            setWindowManager = { windowManager = it },
            removeOverlay = ::removeOverlay,
            addDraggableOverlay = ::addDraggableOverlay,
            showNoteEditor = ::showNoteEditor,
            showBubbleAfterLookup = ::showBubbleIfCallIsStillActive,
            timeoutMs = LOOKUP_POPUP_TIMEOUT_MS,
        )
    }
    private val loadingPopup: PostCallLoadingPopup by lazy {
        PostCallLoadingPopup(
            service = this,
            ui = ui,
            phone = { state.phone },
            title = { state.title },
            subtitle = { state.subtitle },
            setWindowManager = { windowManager = it },
            setLoadingAnimator = { loadingAnimator = it },
            removeOverlay = ::removeOverlay,
            addDraggableOverlay = ::addDraggableOverlay,
            timeoutMs = LOADING_POPUP_TIMEOUT_MS,
        )
    }
    private val bubble: PostCallBubble by lazy {
        PostCallBubble(
            service = this,
            ui = ui,
            setWindowManager = { windowManager = it },
            setOverlayView = { overlayView = it },
            removeOverlay = ::removeOverlay,
            openConfiguredAction = ::openConfiguredPostCallAction,
        )
    }
    private val calendarActions: PostCallCalendarActions by lazy {
        PostCallCalendarActions(
            service = this,
            phone = { state.phone },
            removeOverlay = ::removeOverlay,
            stopOverlay = { stopSelf() },
        )
    }
    private val navigationActions: PostCallNavigationActions by lazy {
        PostCallNavigationActions(
            service = this,
            handler = handler,
            phone = { state.phone },
            title = { state.title },
            removeOverlay = ::removeOverlay,
            stopOverlay = { stopSelf() },
        )
    }
    private val windowController: PostCallOverlayWindowController by lazy {
        PostCallOverlayWindowController(
            service = this,
            handler = handler,
            windowManager = { windowManager },
            setOverlayView = { overlayView = it },
            stopOverlay = { stopSelf() },
            dp = ::dp,
        )
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var loadingAnimator: ObjectAnimator? = null
    private var noteEditorIsVisible = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE).orEmpty()
        // The call-end broadcast arrives through this same service. Replacing an
        // open form here destroys text that is still being entered, so leave that
        // form in place until the user explicitly saves or closes it.
        if (
            mode == MODE_CALL_ENDED &&
            PostCallOverlayEditorTransitionPolicy.keepEditorWhenCallEnds(noteEditorIsVisible)
        ) {
            return START_NOT_STICKY
        }
        state.readExtras(intent)
        // Lookup data is already resolved asynchronously by the coordinator. Do not
        // read Call Log synchronously on the service main thread before first paint.
        if (mode != MODE_LOOKUP) state.hydrateLatestCallIfNeeded(this)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (mode != MODE_LOOKUP) lookupPopup.dismissActiveSession()
        when (mode) {
            MODE_LOADING -> loadingPopup.show()
            MODE_LOOKUP -> lookupPopup.show()
            MODE_NOTE -> showNoteEditor()
            MODE_GENERAL_NOTE -> showGeneralNoteEditor()
            MODE_CALL_ENDED -> showBubbleWithTimeout()
            else -> showBubbleWithTimeout()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loadingAnimator?.cancel()
        loadingAnimator = null
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubbleIfCallIsStillActive() {
        if (CallLifecycleStore.isActive(this, state.phone)) showBubbleUntilCallEnds()
        else stopSelf()
    }

    private fun showBubbleUntilCallEnds() {
        if (shouldShowBubble()) bubble.show()
    }

    private fun showBubbleWithTimeout() {
        if (!shouldShowBubble()) return
        bubble.show()
        val timeout = ConfigStore.load(this).postCallPromptTimeoutSeconds.coerceIn(3, 120)
        handler.postDelayed({ stopSelf() }, timeout * 1000L)
    }

    private fun shouldShowBubble(): Boolean {
        val config = ConfigStore.load(this)
        if (!config.useOverlayPopups || !config.useCustomEndPopup) {
            stopSelf()
            return false
        }
        if (config.postCallEndAction == ConfigStore.POST_CALL_END_ACTION_NOTHING) {
            stopSelf()
            return false
        }
        return true
    }

    private fun openConfiguredPostCallAction() {
        when (ConfigStore.load(this).postCallEndAction) {
            ConfigStore.POST_CALL_END_ACTION_HISTORY -> openContactNotesScreen()
            ConfigStore.POST_CALL_END_ACTION_NOTHING -> stopSelf()
            else -> {
                val remoteFormUrl = state.formUrl.trim()
                if (remoteFormUrl.isNotBlank()) {
                    removeOverlay()
                    PostCallActionRouter.openRemoteForm(this, remoteFormUrl, state.phone, state.direction)
                    stopSelf()
                } else {
                    showNoteEditor()
                }
            }
        }
    }

    private fun showNoteEditor() {
        noteEditorIsVisible = true
        noteEditor.show(UnifiedNoteKind.CALL)
    }

    private fun showGeneralNoteEditor() {
        noteEditorIsVisible = true
        noteEditor.show(UnifiedNoteKind.GENERAL)
    }

    private fun closeNoteEditor() {
        noteEditorIsVisible = false
        if (
            PostCallOverlayEditorTransitionPolicy.showActiveCallBubbleAfterEditorClosed(
                CallLifecycleStore.isActive(this, state.phone),
            )
        ) {
            showBubbleUntilCallEnds()
        } else {
            showBubbleWithTimeout()
        }
    }
    private fun openCalendarEvent(
        displayName: String,
        generalNotes: List<String>,
        currentCallNote: String?,
    ) = calendarActions.openCalendarEvent(displayName, generalNotes, currentCallNote)

    private fun addDraggableOverlay(view: View, focusable: Boolean, defaultY: Int, timeoutMs: Long) {
        windowController.addDraggableOverlay(view, focusable, defaultY, timeoutMs)
    }

    private fun addDraggableOverlay(
        view: View,
        focusable: Boolean,
        defaultY: Int,
        timeoutMs: Long,
        onTimeout: () -> Unit,
    ) {
        windowController.addDraggableOverlay(view, focusable, defaultY, timeoutMs, onTimeout)
    }

    private fun openContactNotesScreen() = navigationActions.openContactNotesScreen()

    private fun notifyNotesChanged() {
        sendBroadcast(Intent(ACTION_NOTES_CHANGED).setPackage(packageName))
    }

    private fun removeOverlay() {
        loadingAnimator?.cancel()
        loadingAnimator = null
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
    }

    private fun dp(value: Int): Int = ui.dp(value)

    companion object {
        const val ACTION_NOTES_CHANGED = "com.onlineimoti.calllog.NOTES_CHANGED"
        const val EXTRA_MODE = "mode"
        const val MODE_LOADING = "loading"
        const val MODE_LOOKUP = "lookup"
        const val MODE_NOTE = "note"
        const val MODE_GENERAL_NOTE = "general_note"
        const val MODE_CALL_ENDED = "call_ended"
        const val EXTRA_FORM_URL = "form_url"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_LINES = "lines"
        const val EXTRA_REMOTE_ROWS_ARE_PRELOADED = "remote_rows_are_preloaded"
        const val EXTRA_POPUP_SESSION_ID = "popup_session_id"
        const val EXTRA_POPUP_UPDATE_ONLY = "popup_update_only"
        const val EXTRA_PROGRESS_CALLS = "progress_calls"
        const val EXTRA_PROGRESS_LOCAL_NOTES = "progress_local_notes"
        const val EXTRA_PROGRESS_SERVER_NOTES = "progress_server_notes"
        const val EXTRA_CALL_AT = "call_at"
        const val EXTRA_DURATION = "duration"
        private const val LOADING_POPUP_TIMEOUT_MS = 45_000L
        private const val LOOKUP_POPUP_TIMEOUT_MS = 30_000L
    }
}
