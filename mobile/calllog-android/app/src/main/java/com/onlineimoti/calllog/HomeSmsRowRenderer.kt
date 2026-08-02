package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import com.google.android.material.card.MaterialCardView

/** Renders SMS timeline cards shown within the combined Call Log. */
internal class HomeSmsRowRenderer(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val noteKey: (String) -> String,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val companyScopeChipsUi: HomeCompanyScopeChipsUi,
    private val openContactNotesScreen: (PhoneCallRecord, String) -> Unit,
    private val noteSyncStatus: (PhoneCallRecord) -> String?,
) {
    private val notesUi by lazy { TimelineNotesUi(activity, dp, roundedRect) }

    fun compactRow(
        call: PhoneCallRecord,
        displayName: String,
        contactNote: String?,
        companyGeneralNoteLabels: List<HomeCompanyScopeLabel>?,
        callNote: HomeCallNote?,
        highlightQuery: String,
        showContactIdentity: Boolean,
        showGeneralContactNote: Boolean,
        serverBacked: Boolean = false,
    ): MaterialCardView {
        val hasContactName = showContactIdentity && displayName.isNotBlank() && noteKey(displayName) != noteKey(call.number)
        val title = displayName.ifBlank { call.displayNumber }
        val metaText = listOf(
            PhoneCallReader.formatStartedAt(call.startedAt),
            call.smsDirectionLabel,
            call.displayNumber.takeIf { hasContactName },
        ).filter { !it.isNullOrBlank() }.joinToString(" • ")
        val crmClient = showGeneralContactNote &&
            CallReportRemoteAccess.isReady(ConfigStore.load(activity.applicationContext)) &&
            CrmContactSyncStore.isEnabled(activity.applicationContext, call.number)
        val highlightedTitle = SearchTextHighlighter.highlightedText(
            title,
            highlightQuery,
            activity.getColor(R.color.calllog_text),
        )

        return SmsTimelineCard.create(
            activity = activity,
            dp = dp,
            message = call,
            displayName = companyScopeChipsUi.inlineCrmIdentity(
                identity = highlightedTitle,
                labels = companyGeneralNoteLabels,
                crmClient = crmClient,
                serverBacked = showGeneralContactNote && serverBacked,
            ),
            metaText = SearchTextHighlighter.highlightedText(metaText, highlightQuery, activity.getColor(R.color.calllog_muted_text)),
            bodyText = SearchTextHighlighter.highlightedText(
                call.smsBody.ifBlank { activity.getString(R.string.dynamic_sms_empty_body) },
                highlightQuery,
                activity.getColor(R.color.calllog_text),
            ),
            showTitle = showContactIdentity,
            actions = emptyList(),
            afterBody = { textColumn ->
                notesUi.addGeneralContactNote(
                    column = textColumn,
                    phone = call.number,
                    contactNote = contactNote,
                    highlightQuery = highlightQuery,
                    visible = showGeneralContactNote,
                )
                notesUi.addCompanyGeneralNotes(
                    column = textColumn,
                    phone = call.number,
                    labels = companyGeneralNoteLabels,
                    highlightQuery = highlightQuery,
                    visible = showGeneralContactNote,
                )
                notesUi.addCallNote(
                    column = textColumn,
                    call = call,
                    callNote = callNote,
                    highlightQuery = highlightQuery,
                    statusForCall = noteSyncStatus,
                    companyLabels = companyGeneralNoteLabels,
                )
            },
            onClick = { openContactNotesScreen(call, displayName) },
        )
    }
}
