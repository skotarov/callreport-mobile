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
    internal val service: Service,
    internal val ui: PostCallOverlayUi,
    internal val phone: () -> String,
    internal val title: () -> String,
    internal val lookupLines: () -> List<String>,
    internal val remoteRowsArePreloaded: () -> Boolean,
    internal val popupSessionId: () -> String,
    internal val popupUpdateOnly: () -> Boolean,
    internal val progressiveRows: () -> IncomingCallPopupProgress,
    internal val setWindowManager: (WindowManager) -> Unit,
    internal val removeOverlay: () -> Unit,
    internal val addDraggableOverlay: (View, Boolean, Int, Long, () -> Unit) -> Unit,
    internal val showNoteEditor: () -> Unit,
    internal val showBubbleAfterLookup: () -> Unit,
    internal val timeoutMs: Long,
) {
    internal val handler = Handler(Looper.getMainLooper())
    /** Invalidates late legacy lookup responses after the overlay was replaced or timed out. */
    internal var activeRequestId = 0L
    internal var activeProgressiveSessionId = ""
    internal var progressiveViews: ProgressiveViews? = null

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

    internal fun identity(phoneValue: String, titleValue: String): String = when {
        titleValue.isNotBlank() && titleValue != phoneValue -> "$titleValue • $phoneValue"
        phoneValue.isNotBlank() -> phoneValue
        else -> titleValue.ifBlank { "Relationship Manager" }
    }

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
