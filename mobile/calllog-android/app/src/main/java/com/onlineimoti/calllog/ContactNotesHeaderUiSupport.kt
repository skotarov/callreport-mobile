package com.onlineimoti.calllog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

internal const val CONTACT_NOTES_CRM_SLOT_START_PADDING_DP = 2
internal const val CONTACT_NOTES_ACTION_ANCHOR_HEIGHT_DP = 50
internal const val CONTACT_NOTES_ACTION_ROW_HEIGHT_DP = 48
internal const val CONTACT_NOTES_CRM_SLOT_WEIGHT = 1.2f

internal fun ContactNotesHeaderUi.identityBlock(
    displayName: String,
    phone: String,
    contactExists: Boolean,
    serverBacked: Boolean,
): LinearLayout {
    return LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(8), 0, dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        if (contactExists && displayName.isNotBlank()) {
            addView(identityPrimaryRow(contactNameText(displayName), serverBacked))
            if (phone.isNotBlank()) addView(phoneNumberText(phone, prominent = false))
        } else if (phone.isNotBlank()) {
            addView(identityPrimaryRow(phoneNumberText(phone, prominent = true), serverBacked))
        }
    }
}

internal fun ContactNotesHeaderUi.identityPrimaryRow(label: TextView, serverBacked: Boolean): LinearLayout =
    LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        if (serverBacked) {
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_cloud_note_filled)
                imageTintList = ColorStateList.valueOf(activity.getColor(R.color.callreport_icon_background))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = if (AppLocaleText.isBulgarian()) "Има сървърен запис" else "Server record exists"
                layoutParams = LinearLayout.LayoutParams(dp(21), dp(21)).apply { marginEnd = dp(5) }
            })
        }
        label.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        addView(label)
    }

internal fun ContactNotesHeaderUi.actionRow(
    phone: String,
    title: String,
    displayName: String,
    contactExists: Boolean,
    contactDescription: String,
    crmSyncAvailable: Boolean,
    crmSyncEnabled: Boolean,
    crmSyncBusy: Boolean,
    crmSyncServerBacked: Boolean,
    openDialer: () -> Unit,
    openCalendarEvent: () -> Unit,
    openDefaultContact: () -> Unit,
    toggleCrmSync: () -> Unit,
): LinearLayout {
    val row = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(5), 0, dp(5))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(CONTACT_NOTES_ACTION_ROW_HEIGHT_DP),
        )
    }
    ContactNotesHeaderActionPolicy.ordered(contactExists).forEachIndexed { index, kind ->
        val button = when (kind) {
            ContactNotesHeaderAction.CRM -> actions.crmSyncButton(
                enabled = crmSyncEnabled,
                busy = crmSyncBusy,
                serverBacked = crmSyncServerBacked,
                available = crmSyncAvailable,
                action = toggleCrmSync,
            )
            ContactNotesHeaderAction.CALENDAR -> actions.iconButton(
                R.drawable.ic_calendar_event,
                activity.getString(R.string.dynamic_action_calendar),
                openCalendarEvent,
            )
            ContactNotesHeaderAction.CONTACT -> actions.iconButton(
                R.drawable.ic_contact_person,
                contactDescription,
                openDefaultContact,
            )
            ContactNotesHeaderAction.ADD_CONTACT -> actions.iconButton(
                R.drawable.ic_contact_person_add,
                contactDescription,
                openDefaultContact,
            )
            ContactNotesHeaderAction.CALL -> actions.iconButton(
                R.drawable.ic_phone_call,
                activity.getString(R.string.dynamic_action_call),
                openDialer,
            )
            ContactNotesHeaderAction.SMS -> actions.iconButton(
                R.drawable.ic_sms_send,
                activity.getString(R.string.dynamic_action_write_sms),
            ) {
                SmsComposeAction.open(
                    activity = activity,
                    phone = phone,
                    title = displayName.ifBlank { title },
                    dp = dp,
                )
            }
        }
        val slotWeight = if (kind == ContactNotesHeaderAction.CRM) CONTACT_NOTES_CRM_SLOT_WEIGHT else 1f
        row.addView(actionSlot(button, insetStart = index == 0, weight = slotWeight))
    }
    return row
}

internal fun ContactNotesHeaderUi.actionRowHostLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    dp(CONTACT_NOTES_ACTION_ROW_HEIGHT_DP),
    Gravity.BOTTOM,
)

internal fun ContactNotesHeaderUi.actionSlot(button: View, insetStart: Boolean, weight: Float): LinearLayout {
    button.layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.MATCH_PARENT,
    )
    return LinearLayout(activity).apply {
        gravity = Gravity.CENTER
        orientation = LinearLayout.HORIZONTAL
        if (insetStart) setPadding(dp(CONTACT_NOTES_CRM_SLOT_START_PADDING_DP), 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        addView(button)
    }
}

internal fun ContactNotesHeaderUi.openChatSettings() {
    activity.startActivity(
        Intent(activity, MainActivity::class.java)
            .putExtra(MainSettingsNavigationController.EXTRA_OPEN_CHATS, true),
    )
}

internal fun ContactNotesHeaderUi.displayNameFromTitle(title: String, phone: String): String {
    val value = title.trim()
    if (value.isBlank() || value == activity.getString(R.string.dynamic_notes_default_title)) return ""
    if (phone.isNotBlank()) {
        if (value == phone) return ""
        if (value.contains("|")) return value.substringAfterLast("|").trim()
        if (value.startsWith(phone)) {
            return value.removePrefix(phone).trim().trimStart('|', '•', '-', '–').trim()
        }
    }
    return value
}

internal fun ContactNotesHeaderUi.titleRow(textValue: String): LinearLayout = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, dp(14), 0, dp(8))
    addView(TextView(activity).apply {
        text = textValue
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(30, 41, 59))
    })
}

internal fun ContactNotesHeaderUi.phoneNumberText(phone: String, prominent: Boolean): TextView = TextView(activity).apply {
    text = phone
    textSize = if (prominent) 20f else 15f
    typeface = if (prominent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    setTextColor(if (prominent) Color.rgb(15, 23, 42) else Color.rgb(71, 85, 105))
    gravity = Gravity.CENTER
    textAlignment = View.TEXT_ALIGNMENT_CENTER
    maxLines = 1
    ellipsize = android.text.TextUtils.TruncateAt.END
    isClickable = true
    isFocusable = true
    setPadding(dp(8), dp(3), dp(8), dp(3))
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
    setOnClickListener {
        copyToClipboard(
            activity.getString(R.string.dynamic_clipboard_phone_label),
            phone,
            activity.getString(R.string.dynamic_phone_copied),
        )
    }
}

internal fun ContactNotesHeaderUi.contactNameText(displayName: String): TextView = TextView(activity).apply {
    text = displayName
    textSize = 22f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(Color.rgb(15, 23, 42))
    gravity = Gravity.CENTER
    textAlignment = View.TEXT_ALIGNMENT_CENTER
    maxLines = 2
    ellipsize = null
    isClickable = true
    isFocusable = true
    setPadding(dp(8), dp(3), dp(8), dp(3))
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
    setOnClickListener {
        copyToClipboard(
            activity.getString(R.string.dynamic_clipboard_name_label),
            displayName,
            activity.getString(R.string.dynamic_name_copied),
        )
    }
}

internal fun ContactNotesHeaderUi.copyToClipboard(label: String, value: String, message: String) {
    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
