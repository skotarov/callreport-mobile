package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Shared Local + company note fields used by fullscreen and floating editors. */
internal class ContactNoteMultiScopeFieldsUi(
    private val context: Context,
    private val dp: (Int) -> Int,
) {
    fun create(
        state: ContactNoteTopicState,
        kind: UnifiedNoteKind,
        textFor: (String) -> String,
        onInputReady: (String, EditText) -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        tag = FIELD_TAG
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        bind(this, state, kind, textFor, onInputReady)
    }

    fun bind(
        container: LinearLayout,
        state: ContactNoteTopicState,
        kind: UnifiedNoteKind,
        textFor: (String) -> String,
        onInputReady: (String, EditText) -> Unit,
    ) {
        container.removeAllViews()

        addField(
            container = container,
            companyId = ContactNoteTopicState.LOCAL_COMPANY_ID,
            label = if (AppLocaleText.isBulgarian()) "Лична" else "Personal",
            kind = kind,
            text = textFor(ContactNoteTopicState.LOCAL_COMPANY_ID),
            serverBacked = false,
            onInputReady = onInputReady,
        )

        state.companies
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .forEach { company ->
                addField(
                    container = container,
                    companyId = company.id,
                    label = company.name.ifBlank { company.id },
                    kind = kind,
                    text = textFor(company.id),
                    serverBacked = true,
                    onInputReady = onInputReady,
                )
            }

        val status = when {
            state.loading -> if (AppLocaleText.isBulgarian()) "Зареждат се фирмите…" else "Loading companies…"
            state.usingCachedCompanies -> context.getString(R.string.dynamic_note_companies_cached_offline)
            state.loadError.isNotBlank() -> context.getString(R.string.dynamic_note_companies_unavailable_deferred)
            else -> ""
        }
        if (status.isNotBlank()) {
            container.addView(TextView(context).apply {
                text = status
                textSize = 12f
                setTextColor(if (state.loadError.isNotBlank() || state.usingCachedCompanies) Color.rgb(146, 64, 14) else Color.rgb(100, 116, 139))
                setPadding(dp(2), dp(5), dp(2), 0)
            })
        }
    }

    private fun addField(
        container: LinearLayout,
        companyId: String,
        label: String,
        kind: UnifiedNoteKind,
        text: String,
        serverBacked: Boolean,
        onInputReady: (String, EditText) -> Unit,
    ) {
        val colors = if (kind.isGeneral) NoteUiStyle.General else NoteUiStyle.Call
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = if (container.childCount == 0) 0 else dp(8) }
        }
        wrapper.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                this.text = label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(55, 65, 81))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (serverBacked) {
                addView(TextView(context).apply {
                    this.text = if (AppLocaleText.isBulgarian()) "сървър" else "server"
                    textSize = 11f
                    setTextColor(Color.rgb(100, 116, 139))
                    setPadding(dp(6), 0, 0, 0)
                })
            }
        })
        val input = EditText(context).apply {
            setText(text)
            setSelection(this.text?.length ?: 0)
            minLines = 1
            maxLines = Int.MAX_VALUE
            textSize = 15f
            setTextColor(colors.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = false
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedRect(colors.background, dp(10), colors.border, if (colors.border == Color.TRANSPARENT) 0 else dp(1))
            isFocusable = true
            isFocusableInTouchMode = true
            isLongClickable = true
            tag = companyId
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(3) }
        }
        wrapper.addView(input)
        container.addView(wrapper)
        onInputReady(companyId, input)
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    companion object {
        const val FIELD_TAG = "callreport_multi_scope_fields"
    }
}
