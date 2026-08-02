package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView

internal class ContactNotesCards(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> android.graphics.drawable.GradientDrawable,
    private val directionArrowLabel: (String) -> String,
) {
    fun generalNoteCard(
        textValue: String,
        muted: Boolean,
        serverConfirmed: Boolean,
        syncStatusText: String,
        onClick: () -> Unit,
        authorName: String = "",
        editable: Boolean = true,
        pending: Boolean = false,
    ): LinearLayout {
        val colors = if (pending) NoteUiStyle.Pending else NoteUiStyle.General
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (!muted || pending) background = roundedRect(colors.background, dp(12), colors.border, dp(1))
            isClickable = editable
            isFocusable = editable
            if (editable) setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            if (authorName.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = authorName
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (pending) colors.metaText else activity.getColor(R.color.callreport_icon_background))
                    setPadding(0, 0, 0, dp(5))
                })
            }
            addView(TextView(activity).apply {
                text = textValue
                textSize = 14.5f
                setTextColor(if (muted) colors.mutedText else colors.text)
                if (serverConfirmed && !pending) {
                    setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_cloud_note, 0, 0, 0)
                }
                compoundDrawablePadding = dp(6)
            })
            if (syncStatusText.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = syncStatusText
                    textSize = 12f
                    setTextColor(
                        if (syncStatusText.startsWith("Синхронизацията не е потвърдена:")) {
                            Color.rgb(185, 28, 28)
                        } else {
                            NoteUiStyle.Pending.metaText
                        }
                    )
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }
    }

    fun addCallNoteButton(call: PhoneCallRecord, onClick: () -> Unit): LinearLayout {
        val colors = NoteUiStyle.Call
        val mutedLinkColor = NoteUiStyle.General.mutedText
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(TextView(activity).apply {
                text = activity.getString(R.string.dynamic_notes_add_latest_call)
                textSize = 14.5f
                setTextColor(mutedLinkColor)
            })
            addView(TextView(activity).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(call.startedAt),
                    directionArrowLabel(call.direction),
                    PhoneCallReader.formatDuration(call.durationSeconds),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(colors.metaText)
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    fun callNoteCard(
        note: ContactCallNote,
        serverConfirmed: Boolean,
        onClick: () -> Unit,
        pending: Boolean = false,
    ): LinearLayout {
        val colors = if (pending) NoteUiStyle.Pending else NoteUiStyle.Call
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(colors.background, dp(12), colors.border, dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(TextView(activity).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(note.callAt.takeIf { it > 0L } ?: note.savedAt),
                    directionArrowLabel(note.direction),
                    PhoneCallReader.formatDuration(note.durationSeconds),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(colors.metaText)
                if (serverConfirmed && !pending) {
                    setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_cloud_note, 0)
                }
                compoundDrawablePadding = dp(6)
            })
            addView(TextView(activity).apply {
                text = note.note
                textSize = 14.5f
                setTextColor(colors.text)
                setPadding(0, dp(5), 0, 0)
            })
        }
    }
}
