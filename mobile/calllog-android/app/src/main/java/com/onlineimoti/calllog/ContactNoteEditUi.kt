package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView

internal data class ContactNoteEditUiState(
    val phone: String,
    val titleText: String,
    val direction: String,
    val callAt: Long,
    val durationSeconds: Long,
    val isGeneralNote: Boolean,
    val topic: ContactNoteTopicState,
    val willEnableServerSync: Boolean = false,
    /** Present when editing a server-only call note that has no local mirror yet. */
    val initialNoteText: String = "",
    val move: ContactNoteMoveUiState = ContactNoteMoveUiState(),
)

/** Fullscreen host around the same editor body used by the floating overlay. */
internal class ContactNoteEditUi(
    private val activity: Activity,
    private val state: () -> ContactNoteEditUiState,
    private val onTopicSelected: (String, EditText) -> Unit,
    private val onNoteInputReady: (EditText) -> Unit,
    private val onTopicControlReady: (RadioGroup) -> Unit,
    private val onMoveAction: () -> Unit,
    private val saveAndSwitch: (UnifiedNoteKind, String) -> Unit,
    private val saveAndClose: (String) -> Unit,
    private val deleteAndClose: () -> Unit,
    private val saveAndOpenCalendar: (String) -> Unit,
    private val close: (String) -> Unit,
) {
    private val topicFieldUi by lazy { ContactNoteTopicFieldUi(activity, ::dp) }

    fun buildContent(): ScrollView {
        val current = state()
        val (crmText, crmColor) = crmStatus(current)
        val built = UnifiedNoteEditorContentUi(activity, ::dp).build(
            state = UnifiedNoteEditorState(
                kind = if (current.isGeneralNote) UnifiedNoteKind.GENERAL else UnifiedNoteKind.CALL,
                titleText = current.titleText,
                phone = current.phone,
                direction = current.direction,
                callAt = current.callAt,
                durationSeconds = current.durationSeconds,
                noteText = initialText(current),
                crmStatusText = crmText,
                crmStatusColor = crmColor,
            ),
            callbacks = UnifiedNoteEditorCallbacks(
                switchMode = saveAndSwitch,
                save = saveAndClose,
                close = close,
                openCalendar = saveAndOpenCalendar,
                delete = deleteAndClose,
            ),
            beforeInput = { card, input ->
                topicFieldUi.create(
                    state = current.topic,
                    onSelected = { companyId -> onTopicSelected(companyId, input) },
                    onControlReady = onTopicControlReady,
                    moveState = current.move,
                    onMoveAction = onMoveAction,
                )?.let(card::addView)
            },
        )
        built.card.background = roundedRect(Color.WHITE, dp(22))
        built.card.elevation = dp(5).toFloat()
        onNoteInputReady(built.input)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setBackgroundColor(Color.rgb(248, 250, 252))
            addView(built.card)
        }
        built.input.requestFocus()
        built.input.postDelayed({
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(built.input, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
        return ScrollView(activity).apply { addView(root) }
    }

    private fun initialText(current: ContactNoteEditUiState): String {
        // initialNoteText is valid only when it is tied to a concrete call. A form
        // opened for a phone/contact without call identity can carry unscoped/main
        // text from its launcher; using that text in the blue tab makes the two tabs
        // appear to share one value. With no call identity, blue must start from its
        // own call-note store (normally empty) instead of borrowing yellow text.
        if (
            !current.isGeneralNote &&
            current.callAt > 0L &&
            current.initialNoteText.isNotBlank()
        ) return current.initialNoteText
        return if (current.isGeneralNote) {
            ContactNoteReader.generalNoteForPhone(activity, current.phone)
        } else {
            ContactNoteReader.callNoteForPhone(activity, current.phone, current.callAt, current.direction)
        }
    }

    private fun crmStatus(current: ContactNoteEditUiState): Pair<String, Int> {
        val enabled = CrmContactSyncStore.isEnabled(activity, current.phone)
        return when {
            enabled -> activity.getString(R.string.dynamic_note_crm_enabled) to Color.rgb(20, 83, 45)
            current.willEnableServerSync -> activity.getString(R.string.note_server_sync_will_be_enabled) to Color.rgb(20, 83, 45)
            else -> activity.getString(R.string.dynamic_note_local_only) to Color.rgb(107, 114, 128)
        }
    }

    private fun roundedRect(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
