package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat

internal enum class UnifiedNoteKind {
    GENERAL,
    CALL;

    val isGeneral: Boolean get() = this == GENERAL
}

internal data class UnifiedNoteEditorState(
    val kind: UnifiedNoteKind,
    val titleText: String,
    val phone: String,
    val direction: String = "",
    val callAt: Long = 0L,
    val durationSeconds: Long = 0L,
    val noteText: String = "",
    val crmStatusText: String = "",
    val crmStatusColor: Int = Color.rgb(107, 114, 128),
)

internal data class UnifiedNoteEditorCallbacks(
    val switchMode: (UnifiedNoteKind, String) -> Unit,
    val save: (String) -> Unit,
    val close: (String) -> Unit,
    val openCalendar: (String) -> Unit,
    val delete: (() -> Unit)? = null,
    val openHistory: ((String) -> Unit)? = null,
)

internal data class UnifiedNoteEditorContent(
    val card: LinearLayout,
    val input: EditText,
)

/** Shared editor body. Fullscreen and overlay hosts only provide their outer wrapper. */
internal class UnifiedNoteEditorContentUi(
    private val context: Context,
    private val dp: (Int) -> Int,
) {
    fun build(
        state: UnifiedNoteEditorState,
        callbacks: UnifiedNoteEditorCallbacks,
        beforeInput: (LinearLayout, EditText) -> Unit = { _, _ -> },
    ): UnifiedNoteEditorContent {
        val input = noteInput(state)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        card.addView(titleRow(state, input, callbacks))
        card.addView(modeSwitch(state, input, callbacks))
        if (!state.kind.isGeneral && state.callAt > 0L) card.addView(callInfoRow(state))
        beforeInput(card, input)
        card.addView(input)
        card.addView(actionRow(input, callbacks))
        return UnifiedNoteEditorContent(card, input)
    }

    private fun titleRow(
        state: UnifiedNoteEditorState,
        input: EditText,
        callbacks: UnifiedNoteEditorCallbacks,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(if (state.kind.isGeneral) R.drawable.ic_note_lines else R.drawable.ic_chat_note)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(35), dp(35)).apply { marginEnd = dp(8) }
            })
            addView(TextView(context).apply {
                text = context.getString(
                    if (state.kind.isGeneral) R.string.dynamic_note_general_title else R.string.dynamic_note_call_title,
                )
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(17, 24, 39))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    12,
                    18,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            })
            addView(iconButton(R.drawable.ic_popup_close, context.getString(R.string.dynamic_sms_close)) {
                callbacks.close(input.text?.toString().orEmpty())
            })
        })

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(1) }
            addView(TextView(context).apply {
                text = state.titleText
                textSize = 13f
                setTextColor(Color.rgb(107, 114, 128))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            })
        })
    }

    private fun modeSwitch(
        state: UnifiedNoteEditorState,
        input: EditText,
        callbacks: UnifiedNoteEditorCallbacks,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(2), dp(2), dp(2), 0)
        background = tabStripBackground(
            Color.rgb(248, 250, 252),
            dp(12),
            Color.rgb(226, 232, 240),
            dp(1),
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }
        addView(modeButton(UnifiedNoteKind.GENERAL, state.kind, input, callbacks))
        addView(modeButton(UnifiedNoteKind.CALL, state.kind, input, callbacks))
    }

    private fun modeButton(
        kind: UnifiedNoteKind,
        selectedKind: UnifiedNoteKind,
        input: EditText,
        callbacks: UnifiedNoteEditorCallbacks,
    ): LinearLayout {
        val selected = kind == selectedKind
        val colors = if (kind.isGeneral) NoteUiStyle.General else NoteUiStyle.Call
        val indicatorColor = if (kind.isGeneral) Color.rgb(245, 158, 11) else colors.border
        val tabBorderColor = Color.rgb(71, 85, 105)
        val label = when {
            AppLocaleText.isBulgarian() && kind.isGeneral -> "Основна"
            AppLocaleText.isBulgarian() -> "Разговор"
            kind.isGeneral -> "Main"
            else -> "Call"
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (selected) {
                activeTabBackground(Color.WHITE, dp(9), tabBorderColor)
            } else {
                inactiveTabBackground(Color.rgb(248, 250, 252), dp(9), tabBorderColor)
            }
            isClickable = !selected
            isFocusable = !selected
            setOnClickListener {
                if (!selected) callbacks.switchMode(kind, input.text?.toString().orEmpty())
            }
            addView(TextView(context).apply {
                text = label
                textSize = 13.5f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
                setTextColor(if (selected) colors.text else Color.rgb(71, 85, 105))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            })
            addView(View(context).apply {
                setBackgroundColor(if (selected) indicatorColor else Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(2),
                ).apply {
                    marginStart = dp(14)
                    marginEnd = dp(14)
                }
            })
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f)
        }
    }

    private fun callInfoRow(state: UnifiedNoteEditorState): TextView = TextView(context).apply {
        text = listOf(
            PhoneCallReader.directionLabel(state.direction),
            PhoneCallReader.formatStartedAt(state.callAt),
            PhoneCallReader.formatDuration(state.durationSeconds),
        ).filter { it.isNotBlank() }.joinToString(" • ")
        textSize = 13f
        setTextColor(Color.rgb(107, 114, 128))
        setPadding(0, dp(6), 0, 0)
    }

    private fun noteInput(state: UnifiedNoteEditorState): EditText {
        val colors = if (state.kind.isGeneral) NoteUiStyle.General else NoteUiStyle.Call
        return EditText(context).apply {
            setText(state.noteText)
            setSelection(text?.length ?: 0)
            minLines = if (state.kind.isGeneral) 4 else 3
            maxLines = 8
            textSize = 16f
            setTextColor(colors.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundedRect(colors.background, dp(12), colors.border, if (colors.border == Color.TRANSPARENT) 0 else dp(1))
            isFocusable = true
            isFocusableInTouchMode = true
            isLongClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
    }

    private fun actionRow(input: EditText, callbacks: UnifiedNoteEditorCallbacks): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
            callbacks.delete?.let { action -> addView(deleteButton(action)) }
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            callbacks.openHistory?.let { action ->
                addView(secondaryButton(if (AppLocaleText.isBulgarian()) "История" else "History") {
                    action(input.text?.toString().orEmpty())
                })
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            }
            addView(primaryButton(context.getString(R.string.dynamic_note_save)) {
                callbacks.save(input.text?.toString().orEmpty())
            })
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            addView(calendarButton {
                callbacks.openCalendar(input.text?.toString().orEmpty())
            })
        }

    private fun iconButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(drawableRes)
            contentDescription = description
            background = roundedRect(Color.rgb(243, 244, 246), dp(18), Color.TRANSPARENT, 0)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) }
        }

    private fun calendarButton(action: () -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = roundedRect(Color.rgb(243, 244, 246), dp(12), Color.TRANSPARENT, 0)
        setPadding(dp(10), dp(7), dp(12), dp(7))
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.dynamic_action_calendar)
        setOnClickListener { action() }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_calendar_event)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) }
        })
        addView(TextView(context).apply {
            text = context.getString(R.string.dynamic_action_calendar)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(55, 65, 81))
        })
    }

    private fun primaryButton(textValue: String, action: () -> Unit): TextView = textButton(
        textValue,
        Color.WHITE,
        Color.rgb(55, 65, 81),
        action,
        horizontalPaddingDp = 20,
    )

    private fun secondaryButton(textValue: String, action: () -> Unit): TextView = textButton(
        textValue, Color.rgb(55, 65, 81), Color.rgb(243, 244, 246), action,
    )

    private fun deleteButton(action: () -> Unit): TextView = textButton(
        context.getString(R.string.dynamic_note_delete), Color.rgb(185, 28, 28), Color.rgb(254, 242, 242), action,
    )

    private fun textButton(
        textValue: String,
        textColor: Int,
        backgroundColor: Int,
        action: () -> Unit,
        horizontalPaddingDp: Int = 12,
    ): TextView = TextView(context).apply {
        text = textValue
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(textColor)
        background = roundedRect(backgroundColor, dp(12), Color.TRANSPARENT, 0)
        setPadding(dp(horizontalPaddingDp), dp(7), dp(horizontalPaddingDp), dp(7))
        setOnClickListener { action() }
    }

    private fun activeTabBackground(color: Int, topRadius: Int, borderColor: Int): LayerDrawable {
        val border = topRoundedRect(borderColor, topRadius)
        val fill = topRoundedRect(color, (topRadius - dp(1)).coerceAtLeast(0))
        return LayerDrawable(arrayOf(border, fill)).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), 0)
        }
    }

    private fun inactiveTabBackground(color: Int, topRadius: Int, bottomBorderColor: Int): LayerDrawable {
        val bottomBorder = topRoundedRect(bottomBorderColor, topRadius)
        val fill = topRoundedRect(color, topRadius)
        return LayerDrawable(arrayOf(bottomBorder, fill)).apply {
            setLayerInset(1, 0, 0, 0, dp(1))
        }
    }

    private fun tabStripBackground(
        color: Int,
        topRadius: Int,
        borderColor: Int,
        borderWidth: Int,
    ): LayerDrawable {
        val border = topRoundedRect(borderColor, topRadius)
        val fill = topRoundedRect(color, (topRadius - borderWidth).coerceAtLeast(0))
        return LayerDrawable(arrayOf(border, fill)).apply {
            setLayerInset(1, borderWidth, borderWidth, borderWidth, 0)
        }
    }

    private fun topRoundedRect(color: Int, topRadius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                topRadius.toFloat(), topRadius.toFloat(),
                topRadius.toFloat(), topRadius.toFloat(),
                0f, 0f,
                0f, 0f,
            )
            setColor(color)
        }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
}
