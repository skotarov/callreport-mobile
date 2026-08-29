package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout

/** Secondary History action row for the installed chat applications selected in Settings. */
internal class ContactNotesChatActionsUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    private val launcher by lazy { ChatAppLauncher(activity) }

    fun row(phone: String): LinearLayout {
        val installedApps = ChatAppVisibilityStore.enabledApps(activity).mapNotNull { app ->
            installedIcon(app)?.let { icon -> app to icon }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(CARD_HORIZONTAL_INSET_DP), dp(CARD_VERTICAL_INSET_DP), dp(CARD_HORIZONTAL_INSET_DP), dp(CARD_VERTICAL_INSET_DP))
            background = cardBackground()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CARD_HEIGHT_DP),
            ).apply {
                marginStart = dp(CARD_HORIZONTAL_MARGIN_DP)
                marginEnd = dp(CARD_HORIZONTAL_MARGIN_DP)
                bottomMargin = dp(CARD_BOTTOM_MARGIN_DP)
            }
            installedApps.forEachIndexed { index, (app, icon) ->
                if (index > 0) addView(divider())
                addView(chatSlot(app, phone, icon))
            }
            visibility = if (installedApps.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun chatSlot(app: ChatApp, phone: String, appIcon: Drawable): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = if (AppLocaleText.isBulgarian()) {
                "Отвори ${app.displayName}"
            } else {
                "Open ${app.displayName}"
            }
            tooltipText = app.displayName
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            addView(ImageView(activity).apply {
                setImageDrawable(appIcon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(CHAT_ICON_SIZE_DP), dp(CHAT_ICON_SIZE_DP)))
            addView(android.widget.TextView(activity).apply {
                text = app.displayName
                textSize = 12f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(activity.getColor(R.color.calllog_muted_text))
                maxLines = 1
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(CHAT_LABEL_HEIGHT_DP),
            ).apply { topMargin = dp(2) })
            setOnClickListener { launcher.open(app, phone) }
        }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(activity.getColor(R.color.calllog_border))
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(CHAT_DIVIDER_HEIGHT_DP))
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(CARD_RADIUS_DP).toFloat()
        setColor(activity.getColor(R.color.calllog_surface))
        setStroke(dp(1), activity.getColor(R.color.calllog_border))
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

    private companion object {
        const val CARD_HORIZONTAL_MARGIN_DP = 16
        const val CARD_HORIZONTAL_INSET_DP = 4
        const val CARD_VERTICAL_INSET_DP = 4
        const val CARD_BOTTOM_MARGIN_DP = 6
        const val CARD_HEIGHT_DP = 70
        const val CARD_RADIUS_DP = 18
        const val CHAT_ICON_SIZE_DP = 34
        const val CHAT_LABEL_HEIGHT_DP = 16
        const val CHAT_DIVIDER_HEIGHT_DP = 38
    }
}
