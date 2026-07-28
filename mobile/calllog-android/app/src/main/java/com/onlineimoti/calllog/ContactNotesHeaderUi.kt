package com.onlineimoti.calllog

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

class ContactNotesHeaderUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    private val actions by lazy { ContactNotesHeaderActionsUi(activity, dp) }

    fun headerRow(
        title: String,
        phone: String,
        contactExists: Boolean,
        showRmCallLogButton: Boolean,
        showCrmSyncButton: Boolean,
        crmSyncEnabled: Boolean,
        crmSyncBusy: Boolean,
        crmSyncServerBacked: Boolean,
        goBack: () -> Unit,
        openDialer: () -> Unit,
        openCalendarEvent: () -> Unit,
        openDefaultContact: () -> Unit,
        openRmContact: () -> Unit,
        toggleCrmSync: () -> Unit,
        openRmCallLog: () -> Unit,
        openRmCallLogFiltered: () -> Unit,
    ): LinearLayout {
        val displayName = displayNameFromTitle(title, phone)
        val compactIdentity = displayName.ifBlank { phone }
        val contactDescription = activity.getString(
            if (contactExists) R.string.dynamic_contact_open else R.string.dynamic_contact_create,
        )
        val identityAnchor = identityBlock(displayName, phone, contactExists)
        val compactTitle = TextView(activity).apply {
            text = compactIdentity
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 23, 42))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.INVISIBLE
            setPadding(dp(4), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            addView(actions.backButton(
                goBack = goBack,
                openCleanCallList = if (showRmCallLogButton) openRmCallLog else null,
            ).apply { layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)) })
            addView(compactTitle)
            addView(actions.iconButton(
                R.drawable.ic_settings_rm_contacts,
                activity.getString(R.string.history_rm_contact),
                openRmContact,
            ).apply { layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)) })
        }
        val createActionRow = {
            actionRow(
                phone = phone,
                title = title,
                displayName = displayName,
                contactExists = contactExists,
                contactDescription = contactDescription,
                crmSyncAvailable = showCrmSyncButton,
                crmSyncEnabled = crmSyncEnabled,
                crmSyncBusy = crmSyncBusy,
                crmSyncServerBacked = crmSyncServerBacked,
                openDialer = openDialer,
                openCalendarEvent = openCalendarEvent,
                openDefaultContact = openDefaultContact,
                toggleCrmSync = toggleCrmSync,
            ).apply {
                setBackgroundColor(activity.getColor(R.color.calllog_bg))
            }
        }
        val actionRow = createActionRow()
        val stickyActionRow = createActionRow()
        val actionAnchor = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ACTION_ANCHOR_HEIGHT_DP),
            )
            addView(actionRow, actionRowHostLayoutParams())
            tag = ContactNotesStickyActions(actionRow, stickyActionRow, topBar, compactTitle)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(topBar)
            addView(identityAnchor)
            addView(actionAnchor)
        }
    }

    fun sectionTitleWithDrawable(textValue: String, drawableRes: Int): LinearLayout {
        return titleRow(textValue).apply {
            addView(ImageView(activity).apply {
                setImageResource(drawableRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) }
            }, 0)
        }
    }

    fun directionArrowLabel(direction: String): String = when (direction) {
        "in" -> activity.getString(R.string.dynamic_direction_in)
        "out" -> activity.getString(R.string.dynamic_direction_out)
        else -> PhoneCallReader.directionLabel(direction)
    }

    private fun identityBlock(displayName: String, phone: String, contactExists: Boolean): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            if (contactExists && displayName.isNotBlank()) {
                addView(contactNameText(displayName))
                if (phone.isNotBlank()) addView(phoneNumberText(phone, prominent = false))
            } else if (phone.isNotBlank()) {
                addView(phoneNumberText(phone, prominent = true))
            }
        }
    }

    private fun actionRow(
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
                dp(ACTION_ROW_HEIGHT_DP),
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
            val slotWeight = if (kind == ContactNotesHeaderAction.CRM) CRM_SLOT_WEIGHT else 1f
            row.addView(actionSlot(button, insetStart = index == 0, weight = slotWeight))
        }
        return row
    }

    private fun actionRowHostLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(ACTION_ROW_HEIGHT_DP),
        Gravity.BOTTOM,
    )

    private fun actionSlot(button: View, insetStart: Boolean, weight: Float): LinearLayout {
        button.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            if (insetStart) setPadding(dp(CRM_SLOT_START_PADDING_DP), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
            addView(button)
        }
    }

    private fun displayNameFromTitle(title: String, phone: String): String {
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

    private fun titleRow(textValue: String): LinearLayout = LinearLayout(activity).apply {
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

    private fun phoneNumberText(phone: String, prominent: Boolean): TextView = TextView(activity).apply {
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

    private fun contactNameText(displayName: String): TextView = TextView(activity).apply {
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

    private fun copyToClipboard(label: String, value: String, message: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val CRM_SLOT_START_PADDING_DP = 2
        const val ACTION_ANCHOR_HEIGHT_DP = 50
        const val ACTION_ROW_HEIGHT_DP = 48
        const val CRM_SLOT_WEIGHT = 1.2f
    }
}
