package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Main-note section with one independent phase row directly below each company note. */
internal class CompanyScopedGeneralNoteSectionUi(
    private val activity: Activity,
    private val headerUi: ContactNotesHeaderUi,
    private val cards: ContactNotesCards,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun add(
        root: LinearLayout,
        localNote: String,
        localNotePending: Boolean,
        companyScopeAvailable: Boolean,
        companyNotes: List<CallReportCompanyMainNote>,
        unscopedServerMainNote: CallReportHistoryEvent?,
        showCompanyNotes: Boolean,
        onEditCompany: (String) -> Unit,
        onEditUnscopedServerMainNote: (CallReportHistoryEvent) -> Unit,
        phaseBarForCompany: ((String) -> View)?,
    ) {
        val section = sectionContainer()
        root.addView(section)
        section.addView(headerUi.sectionTitleWithDrawable(activity.getString(R.string.dynamic_note_general_title), R.drawable.ic_note_lines))
        addLocalNote(section, localNote, localNotePending, onEditCompany)
        addUnscopedServerMainNote(section, unscopedServerMainNote, onEditUnscopedServerMainNote)
        if (!showCompanyNotes || !companyScopeAvailable) return

        companyNotes.forEach { companyNote ->
            val note = companyNote.note.trim()
            section.addView(
                companyHeader(
                    name = companyNote.companyName,
                    showCloud = true,
                    showAdd = note.isBlank(),
                    onAdd = { onEditCompany(companyNote.companyId) },
                ),
            )
            if (note.isNotBlank() || companyNote.pending) {
                val card = cards.generalNoteCard(
                    textValue = note,
                    muted = note.isBlank(),
                    serverConfirmed = companyNote.confirmedByServer,
                    syncStatusText = if (companyNote.pending) activity.getString(R.string.history_pending_server_sync) else "",
                    onClick = { onEditCompany(companyNote.companyId) },
                )
                if (phaseBarForCompany != null) {
                    (card.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(2)
                }
                section.addView(card)
            }
            phaseBarForCompany?.invoke(companyNote.companyId)?.let(section::addView)
        }
    }

    private fun addLocalNote(
        section: LinearLayout,
        noteValue: String,
        pending: Boolean,
        onEditCompany: (String) -> Unit,
    ) {
        val note = noteValue.trim()
        section.addView(
            companyHeader(
                name = activity.getString(R.string.note_local_company),
                showCloud = false,
                showAdd = note.isBlank(),
                onAdd = { onEditCompany(ContactNoteTopicState.LOCAL_COMPANY_ID) },
            ),
        )
        if (note.isNotBlank() || pending) {
            section.addView(
                cards.generalNoteCard(
                    textValue = note,
                    muted = note.isBlank(),
                    serverConfirmed = false,
                    syncStatusText = if (pending) activity.getString(R.string.dynamic_note_pending_company_choice) else "",
                    onClick = { onEditCompany(ContactNoteTopicState.LOCAL_COMPANY_ID) },
                ),
            )
        }
    }

    private fun addUnscopedServerMainNote(
        section: LinearLayout,
        note: CallReportHistoryEvent?,
        onEdit: (CallReportHistoryEvent) -> Unit,
    ) {
        val serverNote = note?.takeIf { it.note.trim().isNotBlank() } ?: return
        section.addView(companyHeader("Без фирма", showCloud = true))
        section.addView(
            cards.generalNoteCard(
                textValue = serverNote.note.trim(),
                muted = false,
                serverConfirmed = true,
                syncStatusText = "",
                onClick = { onEdit(serverNote) },
            ),
        )
    }

    private fun companyHeader(
        name: String,
        showCloud: Boolean = false,
        showAdd: Boolean = false,
        onAdd: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(8), dp(2), dp(3))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        addView(companyLabel(name, showCloud).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        })
        if (showAdd && onAdd != null) {
            addView(TextView(activity).apply {
                text = activity.getString(R.string.dynamic_notes_add_general)
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(activity.getColor(R.color.callreport_icon_background))
                setPadding(dp(12), dp(3), 0, dp(3))
                isClickable = true
                isFocusable = true
                setOnClickListener { onAdd() }
            })
        }
    }

    private fun companyLabel(name: String, showCloud: Boolean): TextView = TextView(activity).apply {
        val activeColor = activity.getColor(R.color.callreport_icon_background)
        text = name
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(activeColor)
        if (showCloud) {
            activity.getDrawable(R.drawable.ic_cloud_note_filled)?.mutate()?.apply {
                setTint(activeColor)
                setBounds(0, 0, dp(14), dp(14))
                setCompoundDrawables(this, null, null, null)
                compoundDrawablePadding = dp(4)
            }
        }
    }

    private fun sectionContainer(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
        background = roundedRect(Color.WHITE, dp(18), Color.rgb(218, 220, 224), dp(1))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(14) }
    }
}
