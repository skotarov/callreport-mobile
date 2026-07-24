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
        if (!visible || contactNote.isNullOrBlank()) return
        val colors = NoteUiStyle.General
        val serverBacked = ServerNoteVisuals.isPrefixed(contactNote)
        val visibleText = ServerNoteVisuals.withoutPrefix(contactNote)
        column.addView(noteCard(
            text = SearchTextHighlighter.highlightedText(visibleText, highlightQuery, colors.text),
            colors = colors,
            maxLines = 2,
            cloudDrawableRes = if (serverBacked) R.drawable.ic_cloud_note else 0,
            localDrawableRes = R.drawable.ic_note_lines,
        ))
    }

    /** Shows each yellow company-scoped main note after the ordinary local main note. */
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
                column.addView(noteCard(
                    text = companyScopedText(companyName, visibleNote, highlightQuery, colors.text),
                    colors = colors,
                    maxLines = 3,
                    // The visible company name already identifies this as a cloud scope.
                    // Do not repeat the same information with a cloud icon.
                ))
            }
    }

    /** Shows Local plus every independent company call note, one blue card per scope. */
    fun addCallNote(
        column: LinearLayout,
        call: PhoneCallRecord,
        callNote: HomeCallNote?,
        highlightQuery: String,
        statusForCall: (PhoneCallRecord) -> String?,
        companyLabels: List<HomeCompanyScopeLabel>? = null,
    ) {
        val notes = callNote?.expandedNotes().orEmpty().filter { it.text.isNotBlank() }
        if (notes.isEmpty()) return
        val colors = NoteUiStyle.Call
        notes.forEach { note ->
            val companyName = companyNameFor(note.companyId, companyLabels)
            val textValue = note.text.trim()
            column.addView(noteCard(
                text = if (companyName.isBlank()) {
                    SearchTextHighlighter.highlightedText(textValue, highlightQuery, colors.text)
                } else {
                    companyScopedText(companyName, textValue, highlightQuery, colors.text)
                },
                colors = colors,
                maxLines = 3,
                // A company label is enough by itself. Only an unscoped server note
                // needs a cloud marker, and that marker is intentionally outlined.
                cloudDrawableRes = if (note.fromServer && note.companyId.isBlank()) {
                    R.drawable.ic_cloud_note
                } else {
                    0
                },
            ))
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

    private fun noteCard(
        text: CharSequence,
        colors: NoteCardColors,
        maxLines: Int,
        cloudDrawableRes: Int = 0,
        localDrawableRes: Int = 0,
    ): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(colors.text)
            textSize = 12.5f
            this.maxLines = maxLines
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = roundedRect(colors.background, dp(9), colors.border, dp(1))
            val drawableRes = when {
                cloudDrawableRes != 0 -> cloudDrawableRes
                localDrawableRes != 0 -> localDrawableRes
                else -> 0
            }
            if (drawableRes != 0) {
                setCompoundDrawablesWithIntrinsicBounds(drawableRes, 0, 0, 0)
                compoundDrawablePadding = dp(4)
                if (cloudDrawableRes != 0) {
                    compoundDrawableTintList = ColorStateList.valueOf(
                        activity.getColor(R.color.callreport_icon_background),
                    )
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        }
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
}
