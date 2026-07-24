package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

internal data class FilteredFullLogEntry(
    val row: CallReportHistoryRow,
    val attachedNotes: List<CallReportHistoryRow> = emptyList(),
)

/** Renders local and server rows for one contact's filtered full history. */
internal class FilteredFullLogRowRenderer(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val openContactNotes: ((PhoneCallRecord, String) -> Unit)?,
    private val openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
) {
    private val metadataUi by lazy { FilteredFullLogMetadataUi(activity, dp) }

    fun rowView(phone: String, entry: FilteredFullLogEntry, remoteEnabled: Boolean): MaterialCardView {
        val row = entry.row
        if (row.kind == CallReportHistoryRowKind.SMS) return smsRowView(entry, remoteEnabled)
        val foreignRecord = remoteEnabled && row.authorIsOtherBroker
        val localCall = row.localCall
        val editableAttachedNote = if (foreignRecord) null else {
            entry.attachedNotes.firstOrNull { it.editable && !it.authorIsOtherBroker }
        }
        val card = MaterialCardView(activity).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(when {
                foreignRecord -> FilteredFullLogStyle.foreignBorder
                row.kind == CallReportHistoryRowKind.NOTE -> NoteUiStyle.Call.border
                else -> activity.getColor(R.color.calllog_border)
            })
            setCardBackgroundColor(when {
                foreignRecord -> FilteredFullLogStyle.foreignBackground
                row.kind == CallReportHistoryRowKind.NOTE -> NoteUiStyle.Call.background
                else -> activity.getColor(R.color.calllog_surface)
            })
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val column = baseColumn(row, remoteEnabled, foreignRecord)
        addAttachedNotes(
            column = column,
            phone = phone,
            parentCall = localCall,
            notes = entry.attachedNotes,
            remoteEnabled = remoteEnabled,
        )
        bindCardAction(card, phone, row, localCall, foreignRecord)
        if (!foreignRecord && row.kind == CallReportHistoryRowKind.PHONE && localCall != null) {
            card.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                column.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(column)
                addView(noteActionButton(localCall, editableAttachedNote))
            })
        } else {
            card.addView(column)
        }
        return card
    }

    private fun baseColumn(row: CallReportHistoryRow, remoteEnabled: Boolean, foreignRecord: Boolean): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(metadataUi.metaView(row, remoteEnabled))
            if (row.text.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = if (row.kind == CallReportHistoryRowKind.NOTE) noteText(row) else row.text
                    textSize = 14.5f
                    setTextColor(when {
                        foreignRecord -> FilteredFullLogStyle.foreignText
                        row.kind == CallReportHistoryRowKind.NOTE -> NoteUiStyle.Call.text
                        else -> activity.getColor(R.color.calllog_text)
                    })
                    if (row.kind == CallReportHistoryRowKind.NOTE) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(5), 0, 0)
                })
            }
            if (remoteEnabled) {
                metadataUi.addServerAuthor(this, row)
                metadataUi.addServerVersionNotice(this, row)
            }
        }
    }

    private fun bindCardAction(
        card: MaterialCardView,
        phone: String,
        row: CallReportHistoryRow,
        localCall: PhoneCallRecord?,
        foreignRecord: Boolean,
    ) {
        when {
            !foreignRecord && row.kind == CallReportHistoryRowKind.NOTE && row.editable -> {
                val note = editableHomeCallNote(row) ?: return
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener { openRowNoteEditor(phone, row, note, parentCall = null) }
            }
            !foreignRecord && row.kind == CallReportHistoryRowKind.PHONE && localCall != null && openContactNotes != null -> {
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener { openContactNotes.invoke(localCall, localCall.displayName) }
            }
        }
    }

    private fun smsRowView(entry: FilteredFullLogEntry, remoteEnabled: Boolean): MaterialCardView {
        val row = entry.row
        val foreignRecord = remoteEnabled && row.authorIsOtherBroker
        val displayName = ContactGroupFilter.resolveDisplayName(activity, row.phone).orEmpty()
        val sms = PhoneCallRecord(
            number = row.phone,
            name = displayName,
            direction = if (row.direction == "out") "sms_out" else "sms_in",
            startedAt = row.timeMs,
            durationSeconds = 0L,
            smsBody = row.text,
            providerId = row.localSms?.providerId.orEmpty(),
        )
        return SmsTimelineCard.create(
            activity = activity,
            dp = dp,
            message = sms,
            displayName = sms.displayName,
            colors = SmsTimelineCard.Colors(
                background = if (foreignRecord) FilteredFullLogStyle.foreignBackground else activity.getColor(R.color.calllog_surface),
                border = if (foreignRecord) FilteredFullLogStyle.foreignBorder else activity.getColor(R.color.calllog_border),
                title = if (foreignRecord) FilteredFullLogStyle.foreignText else activity.getColor(R.color.calllog_text),
                meta = if (foreignRecord) FilteredFullLogStyle.foreignText else activity.getColor(R.color.calllog_muted_text),
                body = if (foreignRecord) FilteredFullLogStyle.foreignText else activity.getColor(R.color.calllog_text),
            ),
            metaTrailingIconRes = if (remoteEnabled && row.hasServerCopy) R.drawable.ic_cloud_note else 0,
            afterBody = { column ->
                if (remoteEnabled) {
                    metadataUi.addServerAuthor(column, row)
                    metadataUi.addServerVersionNotice(column, row)
                }
            },
        )
    }

    /**
     * Own notes attached to one phone call share one blue container. Each note row
     * remains independently clickable, so a server-only note can be edited in place.
     * Notes authored by another broker stay read-only in their gentle foreign color.
     */
    private fun addAttachedNotes(
        column: LinearLayout,
        phone: String,
        parentCall: PhoneCallRecord?,
        notes: List<CallReportHistoryRow>,
        remoteEnabled: Boolean,
    ) {
        val ownNotes = notes.filterNot { remoteEnabled && it.authorIsOtherBroker }
        if (ownNotes.isNotEmpty()) {
            column.addView(attachedBlueNotesGroup(phone, parentCall, ownNotes, remoteEnabled))
        }
        notes.filter { remoteEnabled && it.authorIsOtherBroker }
            .forEach { note -> column.addView(attachedForeignNoteView(note, remoteEnabled)) }
    }

    private fun attachedBlueNotesGroup(
        phone: String,
        parentCall: PhoneCallRecord?,
        notes: List<CallReportHistoryRow>,
        remoteEnabled: Boolean,
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedRect(
                NoteUiStyle.Call.background,
                dp(10),
                NoteUiStyle.Call.border,
                dp(1),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            notes.forEachIndexed { index, note ->
                addView(attachedBlueNoteRow(phone, parentCall, note, remoteEnabled, index > 0))
            }
        }
    }

    private fun attachedBlueNoteRow(
        phone: String,
        parentCall: PhoneCallRecord?,
        note: CallReportHistoryRow,
        remoteEnabled: Boolean,
        addTopSpace: Boolean,
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, if (addTopSpace) dp(4) else 0, 0, dp(2))
            val editableNote = editableHomeCallNote(note)
            if (editableNote != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    openRowNoteEditor(phone, note, editableNote, parentCall)
                }
            }
            if (note.text.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = noteText(note)
                    textSize = 14f
                    setTextColor(NoteUiStyle.Call.text)
                    setTypeface(typeface, Typeface.BOLD)
                })
            }
            if (remoteEnabled) {
                metadataUi.addServerAuthor(this, note)
                metadataUi.addServerVersionNotice(this, note)
            }
        }
    }

    private fun attachedForeignNoteView(note: CallReportHistoryRow, remoteEnabled: Boolean): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRect(
                FilteredFullLogStyle.foreignBackground,
                dp(10),
                FilteredFullLogStyle.foreignBorder,
                dp(1),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            if (note.text.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = noteText(note)
                    textSize = 14f
                    setTextColor(FilteredFullLogStyle.foreignText)
                    setTypeface(typeface, Typeface.BOLD)
                })
            }
            if (remoteEnabled) {
                metadataUi.addServerAuthor(this, note)
                metadataUi.addServerVersionNotice(this, note)
            }
        }
    }

    private fun noteText(row: CallReportHistoryRow): String {
        val text = row.text.trim()
        val companyName = row.companyName.trim().ifBlank { cachedCompanyName(row.companyId) }
        return if (companyName.isBlank()) text else "[ $companyName ] $text"
    }

    private fun cachedCompanyName(companyId: String): String {
        val id = companyId.trim()
        if (id.isBlank()) return ""
        val config = ConfigStore.load(activity.applicationContext)
        return CallReportTopicCompaniesCache.read(activity.applicationContext, config)
            ?.companies
            ?.firstOrNull { it.id == id }
            ?.name
            ?.trim()
            ?.ifBlank { id }
            ?: id
    }

    private fun noteActionButton(call: PhoneCallRecord, editableAttachedNote: CallReportHistoryRow?): ImageButton {
        return ImageButton(activity).apply {
            setImageResource(R.drawable.ic_chat_note)
            contentDescription = if (editableAttachedNote == null) "Добави бележка" else "Редактирай бележката"
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(36)).apply { marginEnd = dp(8) }
            setOnClickListener {
                val existing = editableAttachedNote?.let(::editableHomeCallNote)
                if (existing != null) openCallNoteEditor(call, call.displayName, existing)
                else openCallNoteEditor(call, call.displayName, null)
            }
        }
    }

    private fun openRowNoteEditor(
        phone: String,
        row: CallReportHistoryRow,
        note: HomeCallNote,
        parentCall: PhoneCallRecord?,
    ) {
        val displayName = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty()
        val localNote = row.localNote
        val event = row.serverEvent
        val call = parentCall ?: PhoneCallRecord(
            number = phone,
            name = displayName,
            direction = localNote?.direction?.ifBlank { row.direction } ?: row.direction,
            startedAt = localNote?.callAt?.takeIf { it > 0L }
                ?: event?.occurredAtMs?.takeIf { it > 0L }
                ?: row.timeMs,
            durationSeconds = localNote?.durationSeconds?.takeIf { it > 0L }
                ?: row.durationSeconds,
        )
        openCallNoteEditor(call, displayName.ifBlank { call.displayName }, note)
    }

    private fun editableHomeCallNote(row: CallReportHistoryRow): HomeCallNote? {
        if (row.kind != CallReportHistoryRowKind.NOTE || !row.editable || row.authorIsOtherBroker) return null
        val localNote = row.localNote
        val event = row.serverEvent
        return HomeCallNote(
            text = row.text.ifBlank { localNote?.note.orEmpty() },
            updatedAtMs = maxOf(
                row.timeMs,
                localNote?.savedAt ?: 0L,
                event?.updatedAtMs ?: 0L,
            ),
            fromServer = event != null || row.locallyConfirmedOnServer ||
                localNote?.serverClientEventId?.isNotBlank() == true,
            companyId = row.companyId.ifBlank { localNote?.companyId.orEmpty() },
            serverClientEventId = event?.clientEventId.orEmpty()
                .ifBlank { localNote?.serverClientEventId.orEmpty() },
        )
    }
}
