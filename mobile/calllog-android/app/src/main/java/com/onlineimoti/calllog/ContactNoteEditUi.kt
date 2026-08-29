package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
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
    private val textForScope: (String) -> String,
    private val onScopeInputReady: (String, EditText) -> Unit,
    private val onFieldsReady: (LinearLayout) -> Unit,
    private val saveAndSwitch: (UnifiedNoteKind, String) -> Unit,
    private val saveAndClose: (String) -> Unit,
    private val saveAndOpenCalendar: (String) -> Unit,
    private val close: (String) -> Unit,
) {
    private val multiFieldsUi by lazy { ContactNoteMultiScopeFieldsUi(activity, ::dp) }

    fun buildContent(): ScrollView {
        val current = state()
        val (crmText, crmColor) = crmStatus(current)
        var firstInput: EditText? = null
        val built = UnifiedNoteEditorContentUi(activity, ::dp).build(
            state = UnifiedNoteEditorState(
                kind = if (current.isGeneralNote) UnifiedNoteKind.GENERAL else UnifiedNoteKind.CALL,
                titleText = current.titleText,
                phone = current.phone,
                direction = current.direction,
                callAt = current.callAt,
                durationSeconds = current.durationSeconds,
                noteText = "",
                crmStatusText = crmText,
                crmStatusColor = crmColor,
            ),
            callbacks = UnifiedNoteEditorCallbacks(
                switchMode = saveAndSwitch,
                save = saveAndClose,
                close = close,
                openCalendar = saveAndOpenCalendar,
                delete = null,
            ),
            beforeInput = { card, _ ->
                val fields = multiFieldsUi.create(
                    state = current.topic,
                    kind = if (current.isGeneralNote) UnifiedNoteKind.GENERAL else UnifiedNoteKind.CALL,
                    textFor = textForScope,
                    onInputReady = { companyId, input ->
                        if (firstInput == null && companyId == ContactNoteTopicState.LOCAL_COMPANY_ID) {
                            firstInput = input
                        }
                        onScopeInputReady(companyId, input)
                    },
                )
                card.addView(fields)
                onFieldsReady(fields)
            },
        )
        // UnifiedNoteEditorContentUi still owns the common title/tabs/actions. Its
        // legacy single input is hidden because Local + every company are rendered
        // by ContactNoteMultiScopeFieldsUi above it.
        built.input.visibility = View.GONE
        built.card.background = roundedRect(Color.WHITE, dp(20))
        built.card.elevation = dp(5).toFloat()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setBackgroundColor(Color.rgb(248, 250, 252))
            addView(built.card)
        }
        firstInput?.let { input ->
            input.requestFocus()
            input.postDelayed({
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }, 250)
        }
        return ScrollView(activity).apply { addView(root) }
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
