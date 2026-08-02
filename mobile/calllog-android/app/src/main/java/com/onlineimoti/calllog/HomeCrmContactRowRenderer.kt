package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/** Compact planning card for an explicitly enabled CRM contact or server-backed contact. */
internal class HomeCrmContactRowRenderer(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val scopeChipsUi: HomeCompanyScopeChipsUi,
    private val openContactNotes: (PhoneCallRecord, String) -> Unit,
    private val openDialer: (String) -> Unit,
) {
    private val notesUi by lazy { TimelineNotesUi(activity, dp, roundedRect) }

    fun compactRow(
        contact: PhoneCallRecord,
        displayName: String,
        contactNote: String?,
        companyLabels: List<HomeCompanyScopeLabel>?,
        latestCallNote: HomeCallNote? = null,
        highlightQuery: String,
    ): MaterialCardView {
        val title = displayName.ifBlank { contact.number }
        val card = MaterialCardView(activity).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(activity.getColor(R.color.calllog_border))
            setCardBackgroundColor(activity.getColor(R.color.calllog_surface))
            cardElevation = 0f
            isClickable = true
            isFocusable = true
            contentDescription = "Отвори CRM историята на $title"
            setOnClickListener { openContactNotes(contact, title) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        card.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(8), dp(9))
            addView(dialButton(contact.number))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(titleView(title, highlightQuery, companyLabels, contact.number))
                addView(numberView(contact.number, highlightQuery))
                notesUi.addGeneralContactNote(
                    column = this,
                    phone = contact.number,
                    contactNote = contactNote,
                    highlightQuery = highlightQuery,
                    visible = true,
                )
                notesUi.addCompanyGeneralNotes(
                    column = this,
                    phone = contact.number,
                    labels = companyLabels,
                    highlightQuery = highlightQuery,
                    visible = true,
                )
                notesUi.addCallNote(
                    column = this,
                    call = contact,
                    callNote = latestCallNote,
                    highlightQuery = highlightQuery,
                    statusForCall = ::noteSyncStatus,
                    companyLabels = companyLabels,
                )
            })
        })
        return card
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

    private fun dialButton(number: String): ImageButton = ImageButton(activity).apply {
        setImageResource(R.drawable.ic_phone_call)
        contentDescription = activity.getString(R.string.dynamic_action_call)
        background = null
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(7), dp(7), dp(7), dp(7))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(40)).apply { marginEnd = dp(5) }
        setOnClickListener { openDialer(number) }
    }

    private fun titleView(
        title: String,
        query: String,
        labels: List<HomeCompanyScopeLabel>?,
        phone: String,
    ): TextView {
        val color = activity.getColor(R.color.calllog_text)
        val crmClient = CallReportRemoteAccess.isReady(ConfigStore.load(activity.applicationContext)) &&
            CrmContactSyncStore.isEnabled(activity.applicationContext, phone)
        return TextView(activity).apply {
            val identity = SearchTextHighlighter.highlightedText(title, query, color)
            text = scopeChipsUi.inlineCrmIdentity(identity, labels, crmClient = crmClient, serverBacked = true)
            setTextColor(color)
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun numberView(number: String, query: String): TextView {
        val color = activity.getColor(R.color.calllog_muted_text)
        return TextView(activity).apply {
            text = SearchTextHighlighter.highlightedText(number, query, color)
            setTextColor(color)
            textSize = 12.5f
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
        }
    }
}