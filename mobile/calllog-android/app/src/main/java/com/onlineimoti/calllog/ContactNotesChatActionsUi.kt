package com.onlineimoti.calllog

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton

/** Secondary History action row for the installed chat applications selected in Settings. */
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
            installedIcon(app)?.let { appIcon ->
                addView(chatButton(app, phone, appIcon))
            }
        }
        visibility = if (childCount == 0) View.GONE else View.VISIBLE
    }

    private fun chatButton(app: ChatApp, phone: String, appIcon: Drawable): MaterialButton =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = ""
            contentDescription = if (AppLocaleText.isBulgarian()) {
                "Отвори ${app.displayName}"
            } else {
                "Open ${app.displayName}"
            }
            tooltipText = app.displayName
            minimumWidth = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            setPadding(dp(8), 0, dp(8), 0)
            icon = appIcon
            iconTint = null
            iconSize = dp(24)
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeWidth = 0
            layoutParams = ViewGroup.MarginLayoutParams(dp(44), dp(40))
            setOnClickListener { launcher.open(app, phone) }
        }

    private fun installedIcon(app: ChatApp): Drawable? {
        app.packageNames.forEach { packageName ->
            val icon = runCatching {
                activity.packageManager.getApplicationIcon(packageName)
            }.getOrNull()
            if (icon != null) return icon
        }
        return null
    }
}
