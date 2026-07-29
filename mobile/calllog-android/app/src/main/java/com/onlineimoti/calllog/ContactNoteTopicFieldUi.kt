package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView

/** Shared destination field used by full-screen and overlay note editors. */
internal class ContactNoteTopicFieldUi(
    private val context: Context,
    private val dp: (Int) -> Int,
) {
    fun create(
        state: ContactNoteTopicState,
        onSelected: (String) -> Unit,
        onControlReady: (RadioGroup) -> Unit,
    ): LinearLayout? {
        if (!state.visible) return null

        val field = LinearLayout(context).apply {
            tag = FIELD_TAG
            orientation = LinearLayout.VERTICAL
            background = roundedTopicSectionBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            }
        }
        val radioGroup = WrappingRadioGroup(context).apply {
            horizontalSpacingPx = dp(8)
            verticalSpacingPx = dp(4)
        }
        val storageTitle = TextView(context).apply {
            text = storageTitleFor(
                ContactNoteTopicSelector.resolvedSelectedCompanyId(state),
                fallbackLocalOnly = state.localOnly,
            )
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(55, 65, 81))
        }
        field.addView(storageTitle)
        if (state.usingCachedCompanies) {
            field.addView(TextView(context).apply {
                text = context.getString(R.string.dynamic_note_companies_cached_offline)
                textSize = 12f
                setTextColor(Color.rgb(146, 64, 14))
                setPadding(0, dp(4), 0, 0)
            })
        } else if (state.loadError.isNotBlank()) {
            field.addView(TextView(context).apply {
                text = context.getString(R.string.dynamic_note_companies_unavailable_deferred)
                textSize = 12f
                setTextColor(Color.rgb(146, 64, 14))
                setPadding(0, dp(4), 0, 0)
            })
        }
        field.addView(radioGroup, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(5) })

        ContactNoteTopicSelector.bind(context, radioGroup, state) { selectedCompanyId ->
            storageTitle.text = storageTitleFor(selectedCompanyId, fallbackLocalOnly = state.localOnly)
            onSelected(selectedCompanyId)
        }
        onControlReady(radioGroup)
        return field
    }

    private fun storageTitleFor(selectedCompanyId: String, fallbackLocalOnly: Boolean): String {
        val local = selectedCompanyId == ContactNoteTopicState.LOCAL_COMPANY_ID ||
            (selectedCompanyId.isBlank() && fallbackLocalOnly)
        return when {
            AppLocaleText.isBulgarian() && local -> "Тази бележка се пази само локално"
            AppLocaleText.isBulgarian() -> "Бележката се пази на сървъра"
            local -> "This note is stored only locally"
            else -> "The note is stored on the server"
        }
    }

    private fun roundedTopicSectionBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(1), Color.rgb(209, 213, 219))
    }

    companion object {
        const val FIELD_TAG = "callreport_topic_field"
    }
}
