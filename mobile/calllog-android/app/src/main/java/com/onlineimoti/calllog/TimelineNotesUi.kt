package com.onlineimoti.calllog

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
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
        contactNote: String?,
        highlightQuery: String,
        visible: Boolean,
    ) {
        if (!visible) return
        val colors = NoteUiStyle.General
        HomeGeneralNoteBundle.entries(contactNote).forEach { entry ->
            addGeneralNoteRow(
                column = column,
                text = SearchTextHighlighter.highlightedText(entry.text, highlightQuery, colors.text),
                colors = colors,
                maxLines = 3,
                drawableRes = if (entry.fromServer) R.drawable.ic_cloud_note else R.drawable.ic_note_lines,
                tintDrawable = entry.fromServer,
            )
        }
    }

    /** Adds every company-scoped main note to the same shared yellow container. */
    fun addCompanyGeneralNotes(
        column: LinearLayout,
        labels: List<HomeCompanyScopeLabel>?,
        highlightQuery: String,
        visible: Boolean,
    ) {
        if (!visible) return
        labels.orEmpty()
            .filter { it.generalNote.isNotBlank() }
            .forEach { label ->
                val colors = NoteUiStyle.General
                val companyName = label.companyName.ifBlank { label.companyId }
                val visibleNote = ServerNoteVisuals.withoutPrefix(label.generalNote)
                addGeneralNoteRow(
                    column = column,
                    text = companyScopedText(companyName, visibleNote, highlightQuery, colors.text),
                    colors = colors,
                    maxLines = 3,
                )
            }
    }

    /** All notes attached to one exact call share one blue container. */
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
        val colors = NoteUiStyle.Call
        val container = callNotesContainer(column, colors)
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
                cloudDrawableRes = if (note.fromServer && note.companyId.isBlank()) {
                    R.drawable.ic_cloud_note
                } else {
                    0
                },
            )
        }
        statusForCall(call)?.let { status ->
            column.addView(TextView(activity).apply {
                text = status
                textSize = 11.5f
                setTextColor(Color.rgb(146, 64, 14))
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
    ) {
        val container = generalNotesContainer(column, colors)
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
    ): LinearLayout = sharedContainer(column, colors, GENERAL_NOTES_CONTAINER_TAG)

    private fun callNotesContainer(
        column: LinearLayout,
        colors: NoteCardColors,
    ): LinearLayout = sharedContainer(column, colors, CALL_NOTES_CONTAINER_TAG)

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
        const val CALL_NOTES_CONTAINER_TAG = "relationship_manager_timeline_call_notes"
    }
}
