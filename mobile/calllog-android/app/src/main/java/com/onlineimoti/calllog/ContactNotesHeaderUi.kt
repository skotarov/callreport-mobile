package com.onlineimoti.calllog

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private val chatActions by lazy { ContactNotesChatActionsUi(activity, dp) }
    private val identityAvatar by lazy { ContactHeaderIdentityAvatarUi(activity, dp) }

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
        syncStatusIndicator: View? = null,
    ): LinearLayout {
        val displayName = displayNameFromTitle(title, phone)
        val namePresentation = ContactNamePresentation.from(displayName)
        val compactIdentity = namePresentation.primary.ifBlank { phone }
        val contactDescription = activity.getString(
            if (contactExists) R.string.dynamic_contact_open else R.string.dynamic_contact_create,
        )
        val identityAnchor = identityBlock(namePresentation, phone, contactExists, crmSyncServerBacked)
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
            if (crmSyncServerBacked) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_cloud_note_filled, 0, 0, 0)
                compoundDrawableTintList = ColorStateList.valueOf(activity.getColor(R.color.callreport_icon_background))
                compoundDrawablePadding = dp(5)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(activity.getColor(R.color.calllog_bg))
            elevation = 0f
            stateListAnimator = null
            addView(actions.backButton(
                goBack = goBack,
                openCleanCallList = if (showRmCallLogButton) openRmCallLog else null,
            ).apply { layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)) })
            addView(compactTitle)
            syncStatusIndicator?.let(::addView)
            addView(actions.historyOverflowButton(
                openRmContact = openRmContact,
                openChatSettings = ::openChatSettings,
            ).apply { layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)) })
        }
        val createActionRow = { presentation: ContactNotesActionRowPresentation ->
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
                presentation = presentation,
            )
        }
        val actionRow = createActionRow(ContactNotesActionRowPresentations.normal)
        val stickyActionRow = createActionRow(ContactNotesActionRowPresentations.sticky)
        val actionAnchor = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ContactNotesActionRowPresentations.normal.hostHeightDp),
            )
            setPadding(dp(ACTION_CARD_HORIZONTAL_MARGIN_DP), dp(ACTION_CARD_VERTICAL_SPACE_DP / 2), dp(ACTION_CARD_HORIZONTAL_MARGIN_DP), dp(ACTION_CARD_VERTICAL_SPACE_DP / 2))
            addView(actionRow, actionRowHostLayoutParams(ContactNotesActionRowPresentations.normal))
            tag = ContactNotesStickyActions(actionRow, stickyActionRow, topBar, compactTitle)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(topBar)
            addView(identityAnchor)
            addView(actionAnchor)
            addView(chatActions.row(phone))
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

    private fun identityBlock(
        namePresentation: ContactNamePresentation,
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
            addView(identityAvatar.create(phone, contactExists, serverBacked).apply {
                layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                    bottomMargin = dp(6)
                }
            })
            if (contactExists && namePresentation.primary.isNotBlank()) {
                addView(identityPrimaryRow(contactNameText(namePresentation.primary, namePresentation.fullName), false))
                namePresentation.secondary.forEach { detail ->
                    addView(contactNameDetailText(detail))
                }
                if (phone.isNotBlank()) addView(phoneNumberText(phone, prominent = false))
            } else if (phone.isNotBlank()) {
                addView(identityPrimaryRow(phoneNumberText(phone, prominent = true), false))
            }
        }
    }

    private fun identityPrimaryRow(label: TextView, serverBacked: Boolean): LinearLayout =
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
        presentation: ContactNotesActionRowPresentation,
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ACTION_CARD_HORIZONTAL_INSET_DP), dp(4), dp(ACTION_CARD_HORIZONTAL_INSET_DP), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(presentation.cardHeightDp),
            )
            background = actionCardBackground()
        }
        ContactNotesHeaderActionPolicy.ordered(contactExists)
            .filterNot { it == ContactNotesHeaderAction.CALL }
            .forEachIndexed { index, kind ->
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
            val label = when (kind) {
                ContactNotesHeaderAction.CRM -> "КЛИЕНТ"
                ContactNotesHeaderAction.CALENDAR -> "СРЕЩА"
                ContactNotesHeaderAction.CONTACT, ContactNotesHeaderAction.ADD_CONTACT -> "КОНТАКТ"
                ContactNotesHeaderAction.SMS -> "СМС"
                ContactNotesHeaderAction.CALL -> ""
            }
            if (index > 0) row.addView(actionDivider())
            row.addView(actionSlot(button, label, presentation.showLabels))
        }
        return row
    }

    private fun actionRowHostLayoutParams(presentation: ContactNotesActionRowPresentation): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(presentation.cardHeightDp),
        Gravity.BOTTOM,
    )

    private fun actionSlot(
        button: View,
        label: String,
        showLabel: Boolean,
    ): LinearLayout {
        button.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(ACTION_BUTTON_HEIGHT_DP),
        )
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            addView(button)
            if (showLabel) {
                addView(TextView(activity).apply {
                    text = label
                    textSize = 12f
                    typeface = Typeface.DEFAULT
                    setTextColor(Color.rgb(100, 116, 139))
                    gravity = Gravity.CENTER
                    maxLines = 1
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(ACTION_LABEL_HEIGHT_DP),
                    )
                })
            }
        }
    }

    private fun actionDivider(): View = View(activity).apply {
        setBackgroundColor(activity.getColor(R.color.calllog_border))
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(ACTION_DIVIDER_HEIGHT_DP))
    }

    private fun actionCardBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ACTION_CARD_RADIUS_DP).toFloat()
        setColor(activity.getColor(R.color.calllog_surface))
        setStroke(dp(1), activity.getColor(R.color.calllog_border))
    }

    private fun openChatSettings() {
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .putExtra(MainSettingsNavigationController.EXTRA_OPEN_CHATS, true),
        )
    }

    private fun displayNameFromTitle(title: String, phone: String): String {
        val value = title.trim()
        if (value.isBlank() || value == activity.getString(R.string.dynamic_notes_default_title)) return ""
        if (phone.isNotBlank()) {
            if (value == phone) return ""
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

    private fun contactNameText(label: String, fullName: String): TextView = TextView(activity).apply {
        text = label
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
                fullName,
                activity.getString(R.string.dynamic_name_copied),
            )
        }
    }

    private fun contactNameDetailText(detail: String): TextView = TextView(activity).apply {
        text = detail
        textSize = 16f
        setTextColor(Color.rgb(71, 85, 105))
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(8), 0, dp(8), dp(2))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun copyToClipboard(label: String, value: String, message: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val ACTION_CARD_HORIZONTAL_MARGIN_DP = 16
        const val ACTION_CARD_VERTICAL_SPACE_DP = 8
        const val ACTION_CARD_HORIZONTAL_INSET_DP = 4
        const val ACTION_CARD_RADIUS_DP = 18
        const val ACTION_BUTTON_HEIGHT_DP = 38
        const val ACTION_LABEL_HEIGHT_DP = 16
        const val ACTION_DIVIDER_HEIGHT_DP = 38
    }
}
