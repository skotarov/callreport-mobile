package com.onlineimoti.calllog

import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class PostCallLookupPopup(
    private val service: Service,
    private val ui: PostCallOverlayUi,
    private val phone: () -> String,
    private val title: () -> String,
    private val lookupLines: () -> List<String>,
    private val remoteRowsArePreloaded: () -> Boolean,
    private val popupSessionId: () -> String,
    private val popupUpdateOnly: () -> Boolean,
    private val progressiveRows: () -> IncomingCallPopupProgress,
    private val setWindowManager: (WindowManager) -> Unit,
    private val removeOverlay: () -> Unit,
    private val addDraggableOverlay: (View, Boolean, Int, Long, () -> Unit) -> Unit,
    private val showNoteEditor: () -> Unit,
    private val showBubbleAfterLookup: () -> Unit,
    private val timeoutMs: Long,
) {
    private val handler = Handler(Looper.getMainLooper())
    /** Invalidates late legacy lookup responses after the overlay was replaced or timed out. */
    private var activeRequestId = 0L
    private var activeProgressiveSessionId = ""
    private var progressiveViews: ProgressiveViews? = null

    fun show() {
        val sessionId = popupSessionId().trim()
        if (sessionId.isNotBlank()) {
            showProgressive(sessionId)
            return
        }

        dismissActiveSession()
        val requestId = ++activeRequestId
        val phoneValue = phone()
        val titleValue = title()
        val preloaded = remoteRowsArePreloaded()
        val cachedRemoteRows = IncomingLookupPopupRowsCache.remoteRowsFor(phoneValue)
        val cachedLocalRows = if (preloaded) IncomingLookupPopupRowsCache.localRowsFor(phoneValue).orEmpty() else null
        renderLegacy(requestId, phoneValue, titleValue, cachedRemoteRows, cachedLocalRows)
        if (cachedRemoteRows.isEmpty() && !preloaded) {
            loadRemoteRows(requestId, phoneValue, titleValue)
        }
    }

    /** Marks the current session closed before another overlay mode replaces it. */
    fun dismissActiveSession() {
        val sessionId = activeProgressiveSessionId
        if (sessionId.isBlank()) return
        IncomingCallPopupSessionStore.dismiss(sessionId)
        activeProgressiveSessionId = ""
        progressiveViews = null
        activeRequestId += 1L
    }

    private fun showProgressive(sessionId: String) {
        if (IncomingCallPopupSessionStore.isDismissed(sessionId)) return
        val phoneValue = phone()
        val identity = identity(phoneValue, title())
        val incomingRows = progressiveRows().normalized()
        val current = progressiveViews
        if (
            activeProgressiveSessionId == sessionId &&
            current != null &&
            current.root.isAttachedToWindow
        ) {
            updateProgressiveViews(current, identity, incomingRows)
            return
        }

        // An update-only intent may recreate a service that Android reclaimed, but
        // it may not recreate a session explicitly dismissed by the user.
        if (popupUpdateOnly() && IncomingCallPopupSessionStore.isDismissed(sessionId)) return
        if (activeProgressiveSessionId.isNotBlank() && activeProgressiveSessionId != sessionId) {
            IncomingCallPopupSessionStore.dismiss(activeProgressiveSessionId)
        }

        activeRequestId += 1L
        removeOverlay()
        setWindowManager(service.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(28), ui.dp(20), ui.dp(24), ui.dp(18))
            ui.stylePopupCard(this)
        }
        val contentRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val contentColumn = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val headerView = TextView(service).apply {
            text = identity
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(17, 24, 39))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        contentColumn.addView(headerView)

        val callsRow = progressiveRow(R.drawable.ic_system_call_log, "Обаждания")
        val localNotesRow = progressiveRow(R.drawable.ic_chat_note, "Локални бележки")
        val serverNotesRow = progressiveRow(R.drawable.ic_cloud_note_filled, "Сървърни бележки")
        contentColumn.addView(callsRow.root)
        contentColumn.addView(localNotesRow.root)
        contentColumn.addView(serverNotesRow.root)

        contentRow.addView(contentColumn)
        contentRow.addView(ui.noteRightAction {
            if (sessionId == activeProgressiveSessionId) dismissActiveSession()
            showNoteEditor()
        })
        card.addView(contentRow)

        val views = ProgressiveViews(
            root = card,
            header = headerView,
            calls = callsRow.value,
            localNotes = localNotesRow.value,
            serverNotes = serverNotesRow.value,
        )
        activeProgressiveSessionId = sessionId
        progressiveViews = views
        updateProgressiveViews(views, identity, incomingRows)
        addDraggableOverlay(ui.shadowScroll(card), false, ui.dp(74), timeoutMs) {
            if (sessionId == activeProgressiveSessionId) {
                dismissActiveSession()
                showBubbleAfterLookup()
            }
        }
    }

    private fun progressiveRow(iconRes: Int, contentDescription: String): ProgressRow {
        val value = TextView(service).apply {
            textSize = 13.5f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, ui.dp(7), 0, 0)
            addView(ImageView(service).apply {
                setImageResource(iconRes)
                this.contentDescription = contentDescription
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply {
                    marginEnd = ui.dp(7)
                }
            })
            addView(value)
        }
        return ProgressRow(root, value)
    }

    private fun updateProgressiveViews(
        views: ProgressiveViews,
        identity: String,
        rows: IncomingCallPopupProgress,
    ) {
        val currentHeader = views.header.text?.toString().orEmpty()
        if (identity.contains(" • ") || !currentHeader.contains(" • ")) {
            views.header.text = identity
        }
        updateProgressValue(views.calls, rows.calls)
        updateProgressValue(views.localNotes, rows.localNotes)
        updateProgressValue(views.serverNotes, rows.serverNotes)
        views.root.requestLayout()
    }

    /** Once a source has real information, a duplicate late loader cannot erase it. */
    private fun updateProgressValue(view: TextView, incoming: String) {
        val safeIncoming = incoming.ifBlank { IncomingCallPopupProgress.LOADING }
        val current = view.text?.toString().orEmpty()
        if (
            safeIncoming == IncomingCallPopupProgress.LOADING &&
            current.isNotBlank() &&
            current != IncomingCallPopupProgress.LOADING
        ) {
            return
        }
        view.text = safeIncoming
        if (safeIncoming == IncomingCallPopupProgress.LOADING) {
            view.setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
            view.setTextColor(Color.rgb(107, 114, 128))
        } else {
            view.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            view.setTextColor(Color.rgb(55, 65, 81))
        }
    }

    /**
     * Unknown numbers are eligible for the lookup. Known phone contacts are only
     * queried when CRM is on. The network task never delays the first popup.
     */
    private fun loadRemoteRows(requestId: Long, phoneValue: String, titleValue: String) {
        try {
            REMOTE_ROWS_EXECUTOR.execute {
                val remoteRows = runCatching {
                    PostCallLookupRemoteRows.load(service.applicationContext, phoneValue)
                }.getOrDefault(emptyList())
                if (remoteRows.isEmpty()) return@execute
                IncomingLookupPopupRowsCache.putRemoteRows(phoneValue, remoteRows)
                handler.post {
                    if (requestId != activeRequestId || phoneValue != phone()) return@post
                    renderLegacy(
                        requestId = requestId,
                        phoneValue = phoneValue,
                        titleValue = titleValue,
                        remoteRows = remoteRows,
                        localRows = null,
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            // A full queue must not block or delay the already visible popup.
        }
    }

    private fun renderLegacy(
        requestId: Long,
        phoneValue: String,
        titleValue: String,
        remoteRows: List<PostCallLookupRemoteRow>,
        /** Non-null means rows were prepared off the UI thread. */
        localRows: List<String>?,
    ) {
        removeOverlay()
        setWindowManager(service.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        val identity = identity(phoneValue, titleValue)
        val content = PostCallLookupDisplayRows.build(
            context = service,
            phone = phoneValue,
            identity = identity,
            remoteRows = remoteRows,
            lookupServerLines = lookupLines(),
            preloadedLocalRows = localRows,
        )

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(28), ui.dp(20), ui.dp(24), ui.dp(18))
            ui.stylePopupCard(this)
        }
        val contentRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val contentColumn = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        contentColumn.addView(TextView(service).apply {
            text = content.header
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(17, 24, 39))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        if (content.rows.isNotEmpty()) {
            contentColumn.addView(buildDataColumn(content.rows))
        }
        contentRow.addView(contentColumn)
        contentRow.addView(ui.noteRightAction { showNoteEditor() })
        card.addView(contentRow)
        addDraggableOverlay(ui.shadowScroll(card), false, ui.dp(74), timeoutMs) {
            if (requestId == activeRequestId) {
                activeRequestId += 1L
                showBubbleAfterLookup()
            }
        }
    }

    private fun identity(phoneValue: String, titleValue: String): String = when {
        titleValue.isNotBlank() && titleValue != phoneValue -> "$titleValue • $phoneValue"
        phoneValue.isNotBlank() -> phoneValue
        else -> titleValue.ifBlank { "Relationship Manager" }
    }

    private fun IncomingCallPopupProgress.normalized() = IncomingCallPopupProgress(
        calls = calls.ifBlank { IncomingCallPopupProgress.LOADING },
        localNotes = localNotes.ifBlank { IncomingCallPopupProgress.LOADING },
        serverNotes = serverNotes.ifBlank { IncomingCallPopupProgress.LOADING },
    )

    private fun buildDataColumn(rows: List<PostCallLookupDisplayRow>): LinearLayout {
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(6), 0, 0)
            rows.forEachIndexed { index, row -> addView(displayRow(row, index)) }
        }
    }

    private fun displayRow(row: PostCallLookupDisplayRow, position: Int): View {
        val topMargin = if (position == 0) 0 else ui.dp(6)
        return when (row.kind) {
            PostCallLookupDisplayRow.Kind.IDENTITY -> TextView(service).apply {
                text = row.text
                textSize = 14f
                setTextColor(Color.rgb(75, 85, 99))
                setPadding(0, topMargin, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            PostCallLookupDisplayRow.Kind.GENERAL_NOTE -> ui.generalNotePreviewRow(row.text, topMargin)
            PostCallLookupDisplayRow.Kind.CALL_NOTE -> ui.notePreviewRow(
                noteText = row.text,
                textColor = NoteUiStyle.Call.text,
                backgroundColor = NoteUiStyle.Call.background,
                strokeColor = NoteUiStyle.Call.border,
                topMargin = topMargin,
                iconRes = R.drawable.ic_chat_note,
            )
            PostCallLookupDisplayRow.Kind.SERVER_INFO -> TextView(service).apply {
                text = row.text
                textSize = 13.5f
                setTextColor(Color.rgb(75, 85, 99))
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_system_call_log, 0, 0, 0)
                compoundDrawablePadding = ui.dp(5)
                setPadding(0, topMargin, 0, 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private data class ProgressRow(val root: LinearLayout, val value: TextView)

    private data class ProgressiveViews(
        val root: View,
        val header: TextView,
        val calls: TextView,
        val localNotes: TextView,
        val serverNotes: TextView,
    )

    private companion object {
        private const val MAX_PENDING_REMOTE_ROWS = 8
        private val REMOTE_ROWS_EXECUTOR = ThreadPoolExecutor(
            2,
            2,
            20L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(MAX_PENDING_REMOTE_ROWS),
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}
