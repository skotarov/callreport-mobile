package com.onlineimoti.calllog

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView

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
        val filledCloud = !enabled && serverBacked
        val cloudColor = when {
            enabled -> Color.WHITE
            filledCloud -> activeColor
            else -> Color.BLACK
        }
        val labelColor = if (enabled) Color.WHITE else Color.BLACK
        val bulgarian = AppLocaleText.isBulgarian()
        val description = when {
            !available -> if (bulgarian) "Личният списък не е достъпен без настроен сървър" else "The personal list is unavailable without a configured server"
            busy -> if (bulgarian) "Променям статуса Мой" else "Changing My status"
            enabled -> if (bulgarian) "Мой активен клиент. Натисни, за да премахнеш" else "My active client. Tap to remove"
            serverBacked -> if (bulgarian) "Има сървърна история. Натисни, за да маркираш като Мой" else "Server history exists. Tap to mark as Mine"
            else -> if (bulgarian) "Маркирай като Мой активен клиент" else "Mark as my active client"
        }
        val cloudIcon = ImageView(activity).apply {
            setImageResource(if (filledCloud) R.drawable.ic_cloud_note_filled else R.drawable.ic_cloud_note)
            imageTintList = ColorStateList.valueOf(cloudColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(3), dp(5), dp(3), dp(5))
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(36))
        }
        val crmLabel = TextView(activity).apply {
            text = if (bulgarian) "Мой" else "Mine"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(labelColor)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            isSingleLine = true
            includeFontPadding = false
            setPadding(0, 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(1), 0)
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
            addView(cloudIcon)
            addView(crmLabel)
            setOnClickListener { action() }
            if (busy) cloudIcon.startAnimation(cloudSpinAnimation())
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

    fun iconButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(activity).apply {
            setImageResource(drawableRes)
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
    }
}
