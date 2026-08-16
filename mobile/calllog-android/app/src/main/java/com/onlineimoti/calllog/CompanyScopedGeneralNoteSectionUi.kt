package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Main-note section showing only existing notes, with one section-level edit action. */
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
        section.addView(generalSectionTitle { onEditCompany("") })
        addLocalNote(section, localNote, localNotePending, onEditCompany)
        addUnscopedServerMainNote(section, unscopedServerMainNote, onEditUnscopedServerMainNote)
        if (!showCompanyNotes) return
        val visibleCompanyNotes = CompanyMainNoteVisibilityPolicy.visibleNotes(
            companyScopeAvailable = companyScopeAvailable,
            notes = companyNotes,
        )
        if (visibleCompanyNotes.isEmpty()) return

        visibleCompanyNotes
            .filter { it.companyId.isNotBlank() }
            .groupBy { it.companyId }
            .values
            .sortedBy { notes -> notes.firstOrNull()?.companyName.orEmpty().lowercase() }
            .forEach { notes ->
                addCompanyNotes(
                    section = section,
                    notes = notes,
                    onEditCompany = onEditCompany,
                    phaseBarForCompany = phaseBarForCompany.takeIf { companyScopeAvailable },
                )
            }
    }

    private fun generalSectionTitle(onEdit: () -> Unit): LinearLayout {
        val row = headerUi.sectionTitleWithDrawable(
            activity.getString(R.string.dynamic_note_general_title),
            R.drawable.ic_note_lines,
        )
        (row.getChildAt(1) as? TextView)?.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        )
        row.addView(TextView(activity).apply {
            text = if (AppLocaleText.isBulgarian()) "Редакция" else "Edit"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(activity.getColor(R.color.callreport_icon_background))
            setPadding(dp(12), dp(3), 0, dp(3))
            activity.getDrawable(R.drawable.ic_edit_pencil)?.mutate()?.apply {
                setTint(activity.getColor(R.color.callreport_icon_background))
                setBounds(0, 0, dp(16), dp(16))
                setCompoundDrawables(this, null, null, null)
                compoundDrawablePadding = dp(5)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onEdit() }
        })
        return row
    }

    private fun addCompanyNotes(
        section: LinearLayout,
        notes: List<CallReportCompanyMainNote>,
        onEditCompany: (String) -> Unit,
        phaseBarForCompany: ((String) -> View)?,
    ) {
        val first = notes.firstOrNull() ?: return
        val companyId = first.companyId
        val companyName = first.companyName.ifBlank { companyId }
        val multiAuthor = notes.any { it.multiAuthor }
        val visibleNotes = notes
            .filter { it.note.trim().isNotBlank() || it.pending }
            .sortedByDescending { it.updatedAtMs }
        if (visibleNotes.isEmpty()) return
        val lastVisibleNote = visibleNotes.lastOrNull()

        section.addView(companyHeader(name = companyName, showCloud = true))
        visibleNotes.forEach { companyNote ->
            val note = companyNote.note.trim()
            val card = cards.generalNoteCard(
                textValue = note,
                muted = note.isBlank(),
                serverConfirmed = companyNote.confirmedByServer,
                syncStatusText = if (companyNote.pending) activity.getString(R.string.history_pending_server_sync) else "",
                onClick = { onEditCompany(companyId) },
                authorName = if (multiAuthor) companyNote.authorBrokerName.trim() else "",
                editable = companyNote.editable,
                pending = companyNote.pending,
            )
            if (phaseBarForCompany != null && companyNote == lastVisibleNote) {
                (card.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(2)
            }
            section.addView(card)
        }
        phaseBarForCompany?.invoke(companyId)?.let(section::addView)
    }

    private fun addLocalNote(
        section: LinearLayout,
        noteValue: String,
        pending: Boolean,
        onEditCompany: (String) -> Unit,
    ) {
        val note = noteValue.trim()
        if (note.isBlank() && !pending) return
        section.addView(
            companyHeader(
                name = activity.getString(R.string.note_local_company),
                showCloud = false,
            ),
        )
        section.addView(
            cards.generalNoteCard(
                textValue = note,
                muted = note.isBlank(),
                serverConfirmed = false,
                syncStatusText = if (pending) activity.getString(R.string.dynamic_note_pending_company_choice) else "",
                onClick = { onEditCompany(ContactNoteTopicState.LOCAL_COMPANY_ID) },
                pending = pending,
            ),
        )
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
