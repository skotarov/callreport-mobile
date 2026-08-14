package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
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
        moveState: ContactNoteMoveUiState = ContactNoteMoveUiState(),
        onMoveAction: () -> Unit = {},
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
            ).apply { topMargin = dp(12) }
        }
        val radioGroup = WrappingRadioGroup(context).apply {
            horizontalSpacingPx = dp(8)
            verticalSpacingPx = dp(4)
        }
        val storageTitle = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(55, 65, 81))
        }
        val moveAction = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(14, 116, 144))
            setPadding(dp(10), dp(4), 0, dp(4))
        }
        field.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(storageTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(moveAction, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        })
        radioGroup.tag = StorageTitleBinding(storageTitle, moveAction)
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

        bind(radioGroup, state, onSelected, moveState, onMoveAction)
        onControlReady(radioGroup)
        return field
    }

    fun bind(
        control: RadioGroup,
        state: ContactNoteTopicState,
        onSelected: (String) -> Unit,
        moveState: ContactNoteMoveUiState = ContactNoteMoveUiState(),
        onMoveAction: () -> Unit = {},
    ) {
        val binding = control.tag as? StorageTitleBinding
        binding?.title?.text = if (moveState.selectingTarget) {
            if (AppLocaleText.isBulgarian()) "Избери фирма, в която да преместиш" else "Choose the destination company"
        } else {
            storageTitleFor(
                ContactNoteTopicSelector.resolvedSelectedCompanyId(state),
                fallbackLocalOnly = state.localOnly,
            )
        }
        binding?.moveAction?.apply {
            visibility = if (moveState.canMove || moveState.selectingTarget || moveState.moving) View.VISIBLE else View.GONE
            text = when {
                moveState.moving -> if (AppLocaleText.isBulgarian()) "Премества..." else "Moving..."
                moveState.selectingTarget -> if (AppLocaleText.isBulgarian()) "Отказ" else "Cancel"
                else -> if (AppLocaleText.isBulgarian()) "Премести" else "Move"
            }
            isEnabled = !moveState.moving
            alpha = if (isEnabled) 1f else 0.55f
            setOnClickListener { if (isEnabled) onMoveAction() }
        }
        ContactNoteTopicSelector.bind(context, control, state) { selectedCompanyId ->
            if (!moveState.selectingTarget) {
                binding?.title?.text = storageTitleFor(
                    selectedCompanyId,
                    fallbackLocalOnly = state.localOnly,
                )
            }
            onSelected(selectedCompanyId)
        }
    }

    private fun storageTitleFor(selectedCompanyId: String, fallbackLocalOnly: Boolean): String {
        val destination = ContactNoteStorageDestinationPolicy.resolve(selectedCompanyId, fallbackLocalOnly)
        return when {
            AppLocaleText.isBulgarian() && destination == ContactNoteStorageDestination.LOCAL ->
                "Лична бележка — пази се само на телефона"
            AppLocaleText.isBulgarian() -> "Фирмена бележка — пази се на сървъра"
            destination == ContactNoteStorageDestination.LOCAL -> "Personal note — stored only on this phone"
            else -> "Company note — stored on the server"
        }
    }

    private fun roundedTopicSectionBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(1), Color.rgb(209, 213, 219))
    }

    private data class StorageTitleBinding(val title: TextView, val moveAction: TextView)

    companion object {
        const val FIELD_TAG = "callreport_topic_field"
    }
}
