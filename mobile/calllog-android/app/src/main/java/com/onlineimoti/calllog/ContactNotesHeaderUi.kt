package com.onlineimoti.calllog

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
    internal val activity: Activity,
    internal val dp: (Int) -> Int,
) {
    internal val actions by lazy { ContactNotesHeaderActionsUi(activity, dp) }
    internal val chatActions by lazy { ContactNotesChatActionsUi(activity, dp) }

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
        val identityAnchor = identityBlock(displayName, phone, contactExists, crmSyncServerBacked)
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
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            addView(actions.backButton(
                goBack = goBack,
                openCleanCallList = if (showRmCallLogButton) openRmCallLog else null,
            ).apply { layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)) })
            addView(compactTitle)
            addView(actions.historyOverflowButton(
                openRmContact = openRmContact,
                openChatSettings = ::openChatSettings,
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
                dp(CONTACT_NOTES_ACTION_ANCHOR_HEIGHT_DP),
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


}
