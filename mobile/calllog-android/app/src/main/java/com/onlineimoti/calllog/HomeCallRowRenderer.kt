package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.CallLog
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/** Renders phone-call rows in the combined Call Log timeline. */
internal class HomeCallRowRenderer(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val noteKey: (String) -> String,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val openContactNotesScreen: (PhoneCallRecord, String) -> Unit,
    private val openContactNotePopupForCall: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    private val openDialer: (String) -> Unit = {},
) {
    private val companyScopeChipsUi by lazy { HomeCompanyScopeChipsUi(activity, dp, roundedRect) }
    private val notesUi by lazy { TimelineNotesUi(activity, dp, roundedRect) }
    private val smsRowRenderer by lazy {
        HomeSmsRowRenderer(
            activity, dp, noteKey, roundedRect, companyScopeChipsUi,
            openContactNotesScreen, ::noteSyncStatus,
        )
    }

    fun compactCallRow(
        call: PhoneCallRecord,
        displayName: String,
        contactNote: String? = null,
        companyGeneralNoteLabels: List<HomeCompanyScopeLabel>? = null,
        callNote: HomeCallNote? = null,
        highlightQuery: String = "",
        showContactIdentity: Boolean = true,
        showGeneralContactNote: Boolean = true,
        serverBacked: Boolean = false,
    ): MaterialCardView {
        if (call.isSms) return smsRowRenderer.compactRow(
            call, displayName, contactNote, companyGeneralNoteLabels, callNote,
            highlightQuery, showContactIdentity, showGeneralContactNote, serverBacked,
        )
        val crmClient = isCrmClient(call, showGeneralContactNote)
        val card = MaterialCardView(activity).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(activity.getColor(R.color.calllog_border))
            setCardBackgroundColor(activity.getColor(R.color.calllog_surface))
            cardElevation = 0f
            isClickable = true
            isFocusable = true
            setOnClickListener { openContactNotesScreen(call, displayName) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        row.addView(callStatusButton(call))
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(callMetaText(call, displayName, highlightQuery, showContactIdentity))
        if (showContactIdentity) {
            column.addView(mainNameRow(call, displayName, highlightQuery, crmClient, companyGeneralNoteLabels, showGeneralContactNote && serverBacked))
        }
        notesUi.addGeneralContactNote(
            column = column,
            contactNote = contactNote,
            highlightQuery = highlightQuery,
            visible = showGeneralContactNote,
        )
        notesUi.addCompanyGeneralNotes(
            column = column,
            labels = companyGeneralNoteLabels,
            highlightQuery = highlightQuery,
            visible = showGeneralContactNote,
        )
        notesUi.addCallNote(
            column = column,
            call = call,
            callNote = callNote,
            highlightQuery = highlightQuery,
            statusForCall = ::noteSyncStatus,
            companyLabels = companyGeneralNoteLabels,
        )
        row.addView(column)
        row.addView(actions(call, displayName, callNote))
        card.addView(row)
        return card
    }

    private fun isCrmClient(call: PhoneCallRecord, visible: Boolean): Boolean {
        return visible && CallReportRemoteAccess.isReady(ConfigStore.load(activity.applicationContext)) &&
            CrmContactSyncStore.isEnabled(activity.applicationContext, call.number)
    }

    private fun callStatusButton(call: PhoneCallRecord) = ImageButton(activity).apply {
        setImageResource(callStatusIcon(call))
        contentDescription = activity.getString(R.string.dynamic_action_call)
        background = null
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(7), dp(7), dp(7), dp(7))
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(36)).apply { marginEnd = dp(6) }
        setOnClickListener { openDialer(call.number) }
    }

    private fun callMetaText(call: PhoneCallRecord, displayName: String, query: String, showIdentity: Boolean): TextView {
        val hasName = showIdentity && displayName.isNotBlank() && noteKey(displayName) != noteKey(call.number)
        val value = listOf(
            PhoneCallReader.formatStartedAt(call.startedAt),
            PhoneCallReader.formatDuration(call.durationSeconds),
            call.displayNumber.takeIf { hasName },
        ).filter { !it.isNullOrBlank() }.joinToString(" • ")
        val color = activity.getColor(R.color.calllog_muted_text)
        return TextView(activity).apply {
            text = SearchTextHighlighter.highlightedText(value, query, color)
            setTextColor(color)
            textSize = 12.5f
            maxLines = 1
        }
    }

    private fun actions(call: PhoneCallRecord, name: String, note: HomeCallNote?) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(3) }
        val editableNote = note?.expandedNotes()?.firstOrNull { it.editable }
        val editable = note == null || editableNote != null
        addView(iconButton(
            R.drawable.ic_chat_note,
            if (editable) activity.getString(R.string.dynamic_action_note) else activity.getString(R.string.runtime_view_only),
            { openContactNotePopupForCall(call, name, editableNote) }, editable,
        ))
    }

    private fun noteSyncStatus(call: PhoneCallRecord): String? {
        if (CallReportDeferredCompanyAssignmentStore.isCallPending(activity, call.number, call.direction, call.startedAt)) {
            return activity.getString(R.string.dynamic_note_pending_company_choice)
        }
        if (CompanyCallNoteOutbox.isCallPending(activity, call.number, call.direction, call.startedAt)) {
            return activity.getString(R.string.dynamic_note_pending_server_sync)
        }
        if (!CallReportTopicNoteOutbox.isCallPending(activity, call.number, call.direction, call.startedAt)) return null
        val failure = CallReportTopicNoteOutbox.lastFailure(activity)
        return if (failure.isBlank()) activity.getString(R.string.dynamic_note_pending_server_sync)
        else activity.getString(R.string.dynamic_note_pending_server_sync_failed, failure)
    }

    private fun mainNameRow(
        call: PhoneCallRecord,
        displayName: String,
        query: String,
        crmClient: Boolean,
        labels: List<HomeCompanyScopeLabel>?,
        serverBacked: Boolean,
    ) = LinearLayout(activity).apply {
        val color = activity.getColor(R.color.calllog_text)
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, 0)
        addView(TextView(activity).apply {
            val identity = SearchTextHighlighter.highlightedText(displayName.ifBlank { call.displayNumber }, query, color)
            text = companyScopeChipsUi.inlineCrmIdentity(identity, labels, crmClient, serverBacked)
            setTextColor(color)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun iconButton(drawable: Int, description: String, action: () -> Unit, enabled: Boolean = true) = ImageButton(activity).apply {
        setImageResource(drawable)
        contentDescription = description
        background = null
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.38f
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(36))
        setOnClickListener { if (enabled) action() }
    }

    private fun callStatusIcon(call: PhoneCallRecord): Int = when (call.callType) {
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> R.drawable.ic_call_missed
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> R.drawable.ic_call_rejected
        CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_outgoing
        CallLog.Calls.INCOMING_TYPE -> R.drawable.ic_call_incoming
        else -> when {
            call.direction == "out" && call.durationSeconds <= 0L -> R.drawable.ic_call_rejected
            call.direction != "out" && call.durationSeconds <= 0L -> R.drawable.ic_call_missed
            call.direction == "out" -> R.drawable.ic_call_outgoing
            else -> R.drawable.ic_call_incoming
        }
    }
}
