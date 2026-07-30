package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal fun PostCallLookupPopup.showProgressive(sessionId: String) {
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
    val localNotesRow = progressiveRow(R.drawable.ic_chat_note, "Локални бележки", maxLines = 4)
    val serverNotesRow = progressiveRow(R.drawable.ic_cloud_note_filled, "Сървърни бележки", maxLines = 6)
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

internal fun PostCallLookupPopup.progressiveRow(iconRes: Int, contentDescription: String, maxLines: Int = 2): ProgressRow {
    val value = TextView(service).apply {
        textSize = 13.5f
        this.maxLines = maxLines
        ellipsize = android.text.TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.08f)
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

internal fun PostCallLookupPopup.updateProgressiveViews(
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
internal fun PostCallLookupPopup.updateProgressValue(view: TextView, incoming: String) {
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

internal fun IncomingCallPopupProgress.normalized() = IncomingCallPopupProgress(
    calls = calls.ifBlank { IncomingCallPopupProgress.LOADING },
    localNotes = localNotes.ifBlank { IncomingCallPopupProgress.LOADING },
    serverNotes = serverNotes.ifBlank { IncomingCallPopupProgress.LOADING },
)

internal data class ProgressRow(val root: LinearLayout, val value: TextView)

internal data class ProgressiveViews(
    val root: View,
    val header: TextView,
    val calls: TextView,
    val localNotes: TextView,
    val serverNotes: TextView,
)
