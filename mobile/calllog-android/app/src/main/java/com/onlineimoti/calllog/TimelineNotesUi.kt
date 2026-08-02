package com.onlineimoti.calllog

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.LinearLayout
import android.widget.TextView

/** Shared note cards for phone-call and SMS timeline rows. */
internal class TimelineNotesUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun addGeneralContactNote(
        column: LinearLayout,
        phone: String,
        contactNote: String?,
        highlightQuery: String,
        visible: Boolean,
    ) {
        if (!visible) return
        val pendingLocal = CallReportNoteOutbox.isGeneralPending(activity, phone) ||
            CallReportDeferredCompanyAssignmentStore.isGeneralPending(activity, phone)
        HomeGeneralNoteBundle.entries(contactNote).forEach { entry ->
            val pending = pendingLocal && !entry.fromServer
            val colors = if (pending) NoteUiStyle.Pending else NoteUiStyle.General
            addGeneralNoteRow(
                column = column,
                text = SearchTextHighlighter.highlightedText(entry.text, highlightQuery, colors.text),
                colors = colors,
                maxLines = 3,
                drawableRes = when {
                    pending -> 0
                    entry.fromServer -> R.drawable.ic_cloud_note
                    else -> R.drawable.ic_note_lines
                },
                tintDrawable = entry.fromServer && !pending,
                pending = pending,
                syncStatusText = if (pending) activity.getString(R.string.dynamic_note_pending_server_sync) else "",
            )
        }
    }

    /** Adds every company-scoped main note, keeping pending rows separate and gray. */
    fun addCompanyGeneralNotes(
        column: LinearLayout,
        phone: String,
        labels: List<HomeCompanyScopeLabel>?,
        highlightQuery: String,
        visible: Boolean,
    ) {
        if (!visible) return
        labels.orEmpty()
            .filter { it.generalNote.isNotBlank() }
            .forEach { label ->
                val pending = CallReportTopicNoteOutbox.isGeneralPending(
                    activity,
                    phone,
                    label.companyId,
                )
                val colors = if (pending) NoteUiStyle.Pending else NoteUiStyle.General
                val companyName = label.companyName.ifBlank { label.companyId }
                val visibleNote = ServerNoteVisuals.withoutPrefix(label.generalNote)
                addGeneralNoteRow(
                    column = column,
                    text = companyScopedText(companyName, visibleNote, highlightQuery, colors.text),
                    colors = colors,
                    maxLines = 3,
                    pending = pending,
                    syncStatusText = if (pending) activity.getString(R.string.dynamic_note_pending_server_sync) else "",
                )
            }
    }

    /** All notes attached to one exact call share one blue or pending-gray container. */
    fun addCallNote(
        column: LinearLayout,
        call: PhoneCallRecord,
        callNote: HomeCallNote?,
        highlightQuery: String,
        statusForCall: (PhoneCallRecord) -> String?,
        companyLabels: List<HomeCompanyScopeLabel>? = null,
    ) {
        val notes = dedupeCallNotes(callNote?.expandedNotes().orEmpty())
        if (notes.isEmpty()) return
        val genericLocalNote = ContactCallNote(
            note = notes.first().text,
            callAt = call.startedAt,
            savedAt = notes.maxOfOrNull { it.updatedAtMs } ?: call.startedAt,
            direction = call.direction,
            durationSeconds = call.durationSeconds,
            clientNoteId = LocalNotesFileStore.clientNoteIdForCall(call.number, call.startedAt, call.direction),
        )
        val pendingStatus = statusForCall(call) ?: when {
            CallReportNoteOutbox.isCallPending(activity, call.number, genericLocalNote) ->
                activity.getString(R.string.dynamic_note_pending_server_sync)
            notes.any { note ->
                CallReportNoteOutbox.isClientEventPending(activity, note.serverClientEventId)
            } -> activity.getString(R.string.dynamic_note_pending_server_sync)
            else -> null
        }
        val pending = pendingStatus != null
        val colors = if (pending) NoteUiStyle.Pending else NoteUiStyle.Call
        val container = callNotesContainer(column, colors, pending)
        notes.forEach { note ->
            val companyName = companyNameFor(note.companyId, companyLabels)
            val textValue = note.text.trim()
            addCallNoteRow(
                container = container,
                text = if (companyName.isBlank()) {
                    SearchTextHighlighter.highlightedText(textValue, highlightQuery, colors.text)
                } else {
                    companyScopedText(companyName, textValue, highlightQuery, colors.text)
                },
                colors = colors,
                cloudDrawableRes = if (!pending && note.fromServer && note.companyId.isBlank()) {
                    R.drawable.ic_cloud_note
                } else {
                    0
                },
            )
        }
        pendingStatus?.let { status ->
            column.addView(TextView(activity).apply {
                text = status
                textSize = 11.5f
                setTextColor(NoteUiStyle.Pending.metaText)
                setPadding(dp(8), dp(4), dp(8), 0)
            })
        }
    }

    private fun addGeneralNoteRow(
        column: LinearLayout,
        text: CharSequence,
        colors: NoteCardColors,
        maxLines: Int,
        drawableRes: Int = 0,
        tintDrawable: Boolean = false,
        pending: Boolean = false,
        syncStatusText: String = "",
    ) {
        val container = generalNotesContainer(column, colors, pending)
        val rowKey = "$drawableRes:${text.toString().trim()}"
        for (index in 0 until container.childCount) {
            if (container.getChildAt(index).tag == rowKey) return
        }
        container.addView(TextView(activity).apply {
            tag = rowKey
            this.text = text
            setTextColor(colors.text)
            textSize = 12.5f
            this.maxLines = maxLines
            setPadding(0, dp(3), 0, dp(3))
            if (drawableRes != 0) {
                setCompoundDrawablesWithIntrinsicBounds(drawableRes, 0, 0, 0)
                compoundDrawablePadding = dp(5)
                if (tintDrawable) {
                    compoundDrawableTintList = ColorStateList.valueOf(
                        activity.getColor(R.color.callreport_icon_background),
                    )
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        if (syncStatusText.isNotBlank()) {
            val statusTag = "pending:$rowKey"
            if ((0 until container.childCount).none { container.getChildAt(it).tag == statusTag }) {
                container.addView(TextView(activity).apply {
                    tag = statusTag
                    this.text = syncStatusText
                    textSize = 11.5f
                    setTextColor(NoteUiStyle.Pending.metaText)
                    setPadding(0, 0, 0, dp(3))
                })
            }
        }
    }

    private fun addCallNoteRow(
        container: LinearLayout,
        text: CharSequence,
        colors: NoteCardColors,
        cloudDrawableRes: Int,
    ) {
        val rowKey = "$cloudDrawableRes:${normalize(text.toString())}"
        for (index in 0 until container.childCount) {
            if (container.getChildAt(index).tag == rowKey) return
        }
        container.addView(TextView(activity).apply {
            tag = rowKey
            this.text = text
            setTextColor(colors.text)
            textSize = 12.5f
            maxLines = 3
            setPadding(0, dp(3), 0, dp(3))
            if (cloudDrawableRes != 0) {
                setCompoundDrawablesWithIntrinsicBounds(cloudDrawableRes, 0, 0, 0)
                compoundDrawablePadding = dp(5)
                compoundDrawableTintList = ColorStateList.valueOf(
                    activity.getColor(R.color.callreport_icon_background),
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
    }

    private fun generalNotesContainer(
        column: LinearLayout,
        colors: NoteCardColors,
        pending: Boolean,
    ): LinearLayout = sharedContainer(
        column,
        colors,
        if (pending) GENERAL_PENDING_NOTES_CONTAINER_TAG else GENERAL_NOTES_CONTAINER_TAG,
    )

    private fun callNotesContainer(
        column: LinearLayout,
        colors: NoteCardColors,
        pending: Boolean,
    ): LinearLayout = sharedContainer(
        column,
        colors,
        if (pending) CALL_PENDING_NOTES_CONTAINER_TAG else CALL_NOTES_CONTAINER_TAG,
    )

    private fun sharedContainer(
        column: LinearLayout,
        colors: NoteCardColors,
        containerTag: String,
    ): LinearLayout {
        for (index in 0 until column.childCount) {
            val child = column.getChildAt(index)
            if (child is LinearLayout && child.tag == containerTag) return child
        }
        return LinearLayout(activity).apply {
            tag = containerTag
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = roundedRect(colors.background, dp(9), colors.border, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        }.also { container -> column.addView(container) }
    }

    private fun dedupeCallNotes(values: List<HomeCallNote>): List<HomeCallNote> {
        val unique = linkedMapOf<String, HomeCallNote>()
        values.filter { it.text.isNotBlank() }.forEach { note ->
            val key = "${note.companyId.trim()}|${normalize(note.text)}"
            val current = unique[key]
            if (
                current == null ||
                current.fromServer && !note.fromServer ||
                current.fromServer == note.fromServer && note.updatedAtMs > current.updatedAtMs
            ) {
                unique[key] = note.copy(relatedNotes = emptyList())
            }
        }
        return unique.values.toList()
    }

    private fun companyScopedText(
        companyName: String,
        note: String,
        highlightQuery: String,
        textColor: Int,
    ): CharSequence {
        val prefix = "[ ${companyName.trim()} ] "
        val rawText = prefix + note.trim()
        return SpannableString(
            SearchTextHighlighter.highlightedText(rawText, highlightQuery, textColor),
        ).apply {
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                prefix.length.coerceAtMost(length),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun companyNameFor(
        companyId: String,
        labels: List<HomeCompanyScopeLabel>?,
    ): String {
        val id = companyId.trim()
        if (id.isBlank()) return ""
        labels.orEmpty().firstOrNull { it.companyId == id }?.companyName?.trim()?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        val config = ConfigStore.load(activity.applicationContext)
        return CallReportTopicCompaniesCache.read(activity.applicationContext, config)
            ?.companies
            ?.firstOrNull { it.id == id }
            ?.name
            ?.trim()
            ?.ifBlank { id }
            ?: id
    }

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()

    private companion object {
        const val GENERAL_NOTES_CONTAINER_TAG = "relationship_manager_timeline_general_notes"
        const val GENERAL_PENDING_NOTES_CONTAINER_TAG = "relationship_manager_timeline_general_pending_notes"
        const val CALL_NOTES_CONTAINER_TAG = "relationship_manager_timeline_call_notes"
        const val CALL_PENDING_NOTES_CONTAINER_TAG = "relationship_manager_timeline_call_pending_notes"
    }
}
