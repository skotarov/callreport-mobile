package com.onlineimoti.calllog

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Edits the application's own RM raw-contact layer. It deliberately does not
 * open the default Contacts app and never changes the user's normal contact.
 */
internal class RmContactFormDialog(
    private val activity: Activity,
) {
    fun show(
        phone: String,
        fallbackTitle: String,
        onSaved: () -> Unit,
    ) {
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return
        if (!RmContactPermissions.canReadAndWriteContacts(activity)) {
            Toast.makeText(activity, "Разреши достъп за четене и запис на контакти.", Toast.LENGTH_SHORT).show()
            return
        }

        val hasExistingRmContact = CrmContactAccountStore.findCallReportRawContactId(activity, normalizedPhone) > 0L
        val initial = RmContactFormStore.read(activity, normalizedPhone, fallbackTitle)
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = AppModalStyle.surface(activity)
        }
        root.addView(TextView(activity).apply {
            text = "RM контакт"
            textSize = 20f
            setTextColor(Color.rgb(15, 23, 42))
            setPadding(0, 0, 0, dp(2))
        })
        root.addView(TextView(activity).apply {
            text = "Данните се пазят само в RM слоя на контактите."
            textSize = 13f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, 0, 0, dp(10))
        })

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        content.addView(label("Основен телефон"))
        content.addView(TextView(activity).apply {
            text = phone
            textSize = 16f
            setTextColor(Color.rgb(30, 41, 59))
            setPadding(dp(2), dp(2), dp(2), dp(8))
        })

        val displayName = textField(content, "Име за показване", initial.displayName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val givenName = textField(content, "Собствено име", initial.givenName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val middleName = textField(content, "Бащино име", initial.middleName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val familyName = textField(content, "Фамилия", initial.familyName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val additionalPhone = textField(content, "Допълнителен телефон", initial.additionalPhone, InputType.TYPE_CLASS_PHONE)
        val organization = textField(content, "Фирма", initial.organization, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val jobTitle = textField(content, "Длъжност", initial.jobTitle, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val emailWork = textField(content, "Служебен e-mail", initial.emailWork, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val note = textField(
            parent = content,
            title = "Бележка",
            value = initial.note,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            multiLine = true,
        )

        val actions = LinearLayout(activity).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val deleteButton = Button(activity).apply {
            text = "Изтрий"
            isAllCaps = false
            setTextColor(Color.rgb(185, 28, 28))
            background = AppModalStyle.secondary(activity)
        }
        val cancelButton = Button(activity).apply {
            text = "Откажи"
            isAllCaps = false
            setTextColor(AppModalStyle.accent(activity))
            background = AppModalStyle.secondary(activity)
            setOnClickListener { dialog.dismiss() }
        }
        val saveButton = Button(activity).apply {
            text = "Запази"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = AppModalStyle.primary(activity)
        }
        if (hasExistingRmContact) actions.addView(deleteButton)
        actions.addView(cancelButton)
        actions.addView(saveButton)
        root.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        deleteButton.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Изтриване на RM контакт")
                .setMessage("Ще бъде изтрит само контактът в RM слоя. Обикновеният телефонен контакт няма да бъде променен. CRM синхронизацията за този номер ще бъде изключена.")
                .setNegativeButton("Откажи", null)
                .setPositiveButton("Изтрий") { _, _ ->
                    deleteButton.isEnabled = false
                    deleteButton.text = "Изтрива…"
                    saveButton.isEnabled = false
                    cancelButton.isEnabled = false
                    Thread {
                        val deleted = RmContactSyncLayerStore.deleteRmContact(activity.applicationContext, normalizedPhone)
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            if (deleted) {
                                Toast.makeText(activity, "RM контактът е изтрит.", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                                onSaved()
                            } else {
                                Toast.makeText(activity, "RM контактът не беше изтрит.", Toast.LENGTH_SHORT).show()
                                deleteButton.isEnabled = true
                                deleteButton.text = "Изтрий"
                                saveButton.isEnabled = true
                                cancelButton.isEnabled = true
                            }
                        }
                    }.start()
                }
                .show()
        }

        saveButton.setOnClickListener {
            val values = RmContactFormValues(
                displayName = displayName.text?.toString().orEmpty().trim(),
                givenName = givenName.text?.toString().orEmpty().trim(),
                middleName = middleName.text?.toString().orEmpty().trim(),
                familyName = familyName.text?.toString().orEmpty().trim(),
                additionalPhone = additionalPhone.text?.toString().orEmpty().trim(),
                organization = organization.text?.toString().orEmpty().trim(),
                jobTitle = jobTitle.text?.toString().orEmpty().trim(),
                emailWork = emailWork.text?.toString().orEmpty().trim(),
                note = note.text?.toString().orEmpty().trim(),
            ).withFallbackName(fallbackTitle, normalizedPhone)

            saveButton.isEnabled = false
            saveButton.text = "Записва…"
            if (hasExistingRmContact) deleteButton.isEnabled = false
            Thread {
                val saved = save(normalizedPhone, values)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (saved) {
                        Toast.makeText(activity, "RM контактът е записан.", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onSaved()
                    } else {
                        Toast.makeText(activity, "RM контактът не беше записан.", Toast.LENGTH_SHORT).show()
                        saveButton.isEnabled = true
                        saveButton.text = "Запази"
                        if (hasExistingRmContact) deleteButton.isEnabled = true
                    }
                }
            }.start()
        }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            AppModalStyle.configureWindow(
                dialog = dialog,
                activity = activity,
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            dialog.window?.apply {
                val width = (activity.resources.displayMetrics.widthPixels * 0.95f).toInt()
                val height = (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
                setLayout(width, height)
            }
        }
        dialog.show()
    }

    private fun save(phone: String, values: RmContactFormValues): Boolean {
        val context = activity.applicationContext
        val saved = CallReportStableCrmContactWriter.save(
            context = context,
            fields = CallReportStableCrmContactWriter.Fields(
                originalPhone = phone,
                displayName = values.displayName,
                additionalPhone = values.additionalPhone,
                organization = values.organization,
                jobTitle = values.jobTitle,
                emailWork = values.emailWork,
                note = values.note,
                groupName = RmContactSyncLayerStore.groupNameForCurrentRules(context, phone),
                givenName = values.givenName,
                middleName = values.middleName,
                familyName = values.familyName,
            ),
        )
        if (saved && CrmContactSyncStore.isEnabled(context, phone)) {
            RmContactSyncLayerStore.applyCloudSyncLabelsIfEnabled(context, phone)
            CallReportSyncScheduler.enqueueCatchUp(context, reason = "rm_contact_form_saved")
        }
        return saved
    }

    private fun textField(
        parent: LinearLayout,
        title: String,
        value: String,
        inputType: Int,
        multiLine: Boolean = false,
    ): EditText {
        parent.addView(label(title))
        return EditText(activity).apply {
            setText(value)
            this.inputType = inputType
            if (multiLine) {
                minLines = 3
                maxLines = 6
                gravity = Gravity.TOP
            } else {
                isSingleLine = true
            }
            AppModalStyle.styleInput(this)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
            parent.addView(this)
        }
    }

    private fun label(value: String): TextView {
        return TextView(activity).apply {
            text = value
            textSize = 13f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(dp(2), dp(8), dp(2), dp(2))
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
