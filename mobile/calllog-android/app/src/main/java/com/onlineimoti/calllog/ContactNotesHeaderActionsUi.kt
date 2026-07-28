package com.onlineimoti.calllog

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu

internal class ContactNotesHeaderActionsUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun crmSyncButton(
        enabled: Boolean,
        busy: Boolean,
        serverBacked: Boolean,
        available: Boolean,
        action: () -> Unit,
    ): LinearLayout {
        val activeColor = activity.getColor(R.color.callreport_icon_background)
        val careColor = if (enabled) Color.WHITE else Color.BLACK
        val bulgarian = AppLocaleText.isBulgarian()
        val description = when {
            !available -> if (bulgarian) "Активният списък не е достъпен без настроен сървър" else "The active-client list is unavailable without a configured server"
            busy -> if (bulgarian) "Променям статуса Активен" else "Changing Active status"
            enabled -> if (bulgarian) "Активен клиент. Натисни, за да премахнеш" else "Active client. Tap to remove"
            serverBacked -> if (bulgarian) "Има сървърна история. Натисни, за да маркираш като Активен" else "Server history exists. Tap to mark as Active"
            else -> if (bulgarian) "Маркирай като Активен клиент" else "Mark as an active client"
        }
        val clientCareIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_client_care)
            imageTintList = ColorStateList.valueOf(careColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(36))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(9), 0)
            background = if (enabled) roundedIconBackground(activeColor) else null
            contentDescription = description
            isClickable = available && !busy
            isFocusable = available && !busy
            isEnabled = available && !busy
            alpha = when {
                !available -> 0.48f
                busy -> 0.78f
                else -> 1f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36),
            )
            addView(clientCareIcon)
            setOnClickListener { action() }
            if (busy) clientCareIcon.startAnimation(cloudSpinAnimation())
        }
    }

    fun backButton(
        goBack: () -> Unit,
        openCleanCallList: (() -> Unit)?,
    ): ImageButton {
        val button = iconButton(
            R.drawable.ic_arrow_back,
            activity.getString(R.string.dynamic_action_back),
            goBack,
        )
        if (openCleanCallList == null) return button
        button.setOnLongClickListener {
            PopupMenu(activity, button).apply {
                menu.add(0, MENU_CLEAN_CALL_LIST, 0, activity.getString(R.string.dynamic_action_all_calls))
                setOnMenuItemClickListener { item ->
                    if (item.itemId == MENU_CLEAN_CALL_LIST) openCleanCallList()
                    true
                }
                show()
            }
            true
        }
        return button
    }

    fun contactMenuButton(
        description: String,
        openDefaultContact: () -> Unit,
        openRmContact: () -> Unit,
    ): ImageButton {
        val button = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_contact_person)
            contentDescription = description
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        button.setOnClickListener {
            PopupMenu(activity, button).apply {
                menu.add(0, MENU_PHONE_CONTACT, 0, activity.getString(R.string.history_phone_contact))
                menu.add(0, MENU_RM_CONTACT, 1, activity.getString(R.string.history_rm_contact))
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        MENU_PHONE_CONTACT -> openDefaultContact()
                        MENU_RM_CONTACT -> openRmContact()
                    }
                    true
                }
                show()
            }
        }
        return button
    }

    fun historyOverflowButton(
        openRmContact: () -> Unit,
        openChatSettings: () -> Unit,
    ): ImageButton {
        val button = iconButton(
            R.drawable.ic_more_vertical,
            activity.getString(R.string.history_more_actions),
        ) {}
        button.setOnClickListener {
            PopupMenu(activity, button).apply {
                menu.add(0, MENU_EDIT_RM, 0, activity.getString(R.string.history_edit_crm))
                    .setIcon(R.drawable.ic_edit_pencil)
                menu.add(0, MENU_CHAT_SETTINGS, 1, activity.getString(R.string.history_chat_settings))
                    .setIcon(R.drawable.ic_settings_chats)
                setForceShowIcon(true)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        MENU_EDIT_RM -> openRmContact()
                        MENU_CHAT_SETTINGS -> openChatSettings()
                    }
                    true
                }
                show()
            }
        }
        return button
    }

    fun iconButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
        val iconRes = if (drawableRes == R.drawable.ic_settings_rm_contacts) {
            R.drawable.ic_edit_pencil
        } else {
            drawableRes
        }
        return ImageButton(activity).apply {
            setImageResource(iconRes)
            contentDescription = description
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { action() }
        }
    }

    private fun cloudSpinAnimation(): RotateAnimation {
        return RotateAnimation(
            0f,
            360f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
        ).apply {
            duration = 720L
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private fun roundedIconBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(color)
        }
    }

    private companion object {
        const val MENU_PHONE_CONTACT = 1
        const val MENU_RM_CONTACT = 2
        const val MENU_CLEAN_CALL_LIST = 3
        const val MENU_EDIT_RM = 4
        const val MENU_CHAT_SETTINGS = 5
    }
}
