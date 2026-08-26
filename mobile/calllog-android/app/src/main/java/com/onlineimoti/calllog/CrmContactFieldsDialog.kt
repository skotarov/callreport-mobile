package com.onlineimoti.calllog

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView

object CrmContactFieldsDialog {
    fun show(
        activity: Activity,
        phone: String,
        titleText: String,
        currentGeneralNote: String,
        onSave: (CallReportStableCrmContactWriter.Fields) -> Unit,
    ) {
        val savedFields = CrmContactFieldsReader.load(activity, phone)
        val ui = CrmContactDialogUi(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(4))
        }
        val basicSection = ui.verticalSection()
        val advancedSection = ui.verticalSection().apply { visibility = View.GONE }

        val basicRadio = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "Основни"
            isChecked = true
        }
        val advancedRadio = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "Разширен"
        }
        root.addView(RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, ui.dp(8))
            addView(basicRadio)
            addView(advancedRadio)
            setOnCheckedChangeListener { _, checkedId ->
                advancedSection.visibility = if (checkedId == advancedRadio.id) View.VISIBLE else View.GONE
            }
        })
        root.addView(basicSection)
        root.addView(advancedSection)

        val basicInputs = CrmBasicContactInputs.build(
            ui = ui,
            parent = basicSection,
            phone = phone,
            titleText = titleText,
            currentGeneralNote = currentGeneralNote,
            savedFields = savedFields,
        )
        val advancedInputs = CrmAdvancedContactInputs.build(ui, advancedSection, savedFields)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (savedFields == null) "RM контакт" else "Редакция на RM контакт")
            .setView(ScrollView(activity).apply { addView(root) })
            .setNegativeButton("Изход", null)
            .setPositiveButton("Запис") { _, _ ->
                onSave(advancedInputs.applyTo(basicInputs.toFields(phone)))
            }
            .create()
        dialog.setOnShowListener { AppModalStyle.styleAlert(dialog) }
        dialog.show()
    }
}
