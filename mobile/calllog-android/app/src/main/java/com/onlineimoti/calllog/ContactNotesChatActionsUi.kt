package com.onlineimoti.calllog

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton

/** Secondary History action row for the chat applications selected in Settings. */
internal class ContactNotesChatActionsUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    private val launcher by lazy { ChatAppLauncher(activity) }

    fun row(phone: String): WrappingActionLayout = WrappingActionLayout(activity).apply {
        horizontalSpacingPx = dp(8)
        verticalSpacingPx = dp(8)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        ChatAppVisibilityStore.enabledApps(activity).forEach { app ->
            addView(chatButton(app, phone))
        }
        visibility = if (childCount == 0) View.GONE else View.VISIBLE
    }

    private fun chatButton(app: ChatApp, phone: String): MaterialButton =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = app.displayName
            contentDescription = if (AppLocaleText.isBulgarian()) {
                "Отвори ${app.displayName}"
            } else {
                "Open ${app.displayName}"
            }
            isAllCaps = false
            textSize = 13f
            minimumWidth = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            setPadding(dp(10), 0, dp(12), 0)
            icon = activity.getDrawable(
                if (app == ChatApp.VIBER) R.drawable.ic_chat_viber else R.drawable.ic_chat_app,
            )
            iconTint = ColorStateList.valueOf(app.brandColor)
            iconSize = dp(20)
            iconPadding = dp(6)
            setTextColor(app.brandColor)
            backgroundTintList = ColorStateList.valueOf(
                ColorUtils.blendARGB(Color.WHITE, app.brandColor, 0.06f),
            )
            strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(app.brandColor, 90))
            strokeWidth = dp(1)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40),
            )
            setOnClickListener { launcher.open(app, phone) }
        }
}
