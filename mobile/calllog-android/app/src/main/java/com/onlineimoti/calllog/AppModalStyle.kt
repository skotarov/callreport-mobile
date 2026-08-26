package com.onlineimoti.calllog

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText

/** Shared visual language for the app-owned forms; it never changes their actions or fields. */
internal object AppModalStyle {
    private const val SURFACE = 0xFFFFFFFF.toInt()
    private const val SOFT_SURFACE = 0xFFF8FAFC.toInt()
    private const val BORDER = 0xFFE2E8F0.toInt()
    private const val TEXT = 0xFF0F172A.toInt()
    private const val MUTED = 0xFF475569.toInt()

    fun accent(context: Context): Int = context.getColor(R.color.calllog_accent)

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun surface(context: Context, radiusDp: Int = 26): GradientDrawable = rounded(
        color = SURFACE,
        radiusPx = dp(context, radiusDp),
        strokeColor = Color.TRANSPARENT,
        strokeWidthPx = 0,
    )

    fun input(context: Context, radiusDp: Int = 14): GradientDrawable = rounded(
        color = SOFT_SURFACE,
        radiusPx = dp(context, radiusDp),
        strokeColor = BORDER,
        strokeWidthPx = dp(context, 1),
    )

    fun secondary(context: Context, radiusDp: Int = 14): GradientDrawable = rounded(
        color = SURFACE,
        radiusPx = dp(context, radiusDp),
        strokeColor = BORDER,
        strokeWidthPx = dp(context, 1),
    )

    fun primary(context: Context, radiusDp: Int = 14): GradientDrawable = rounded(
        color = accent(context),
        radiusPx = dp(context, radiusDp),
        strokeColor = Color.TRANSPARENT,
        strokeWidthPx = 0,
    )

    fun styleInput(input: EditText) {
        input.setTextColor(TEXT)
        input.setHintTextColor(MUTED)
        input.setPadding(dp(input.context, 12), dp(input.context, 10), dp(input.context, 12), dp(input.context, 10))
        input.background = this.input(input.context)
    }

    fun configureWindow(
        dialog: Dialog,
        activity: Activity,
        topAligned: Boolean = false,
        softInputMode: Int? = null,
    ) {
        dialog.window?.apply {
            setBackgroundDrawable(surface(activity))
            setGravity(if (topAligned) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER)
            if (topAligned) attributes = attributes.apply { y = dp(activity, 16) }
            setLayout(activity.resources.displayMetrics.widthPixels - dp(activity, 32), ViewGroup.LayoutParams.WRAP_CONTENT)
            softInputMode?.let(::setSoftInputMode)
        }
    }

    fun styleAlert(dialog: android.app.AlertDialog) {
        dialog.window?.setBackgroundDrawable(surface(dialog.context))
        styleButtons(
            context = dialog.context,
            positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE),
            negative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE),
            neutral = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL),
        )
    }

    fun styleAlert(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.window?.setBackgroundDrawable(surface(dialog.context))
        styleButtons(
            context = dialog.context,
            positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE),
            negative = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE),
            neutral = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL),
        )
    }

    private fun styleButtons(context: Context, positive: Button?, negative: Button?, neutral: Button?) {
        positive?.apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = primary(context, radiusDp = 12)
            backgroundTintList = ColorStateList.valueOf(accent(context))
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
        }
        listOfNotNull(negative, neutral).forEach { button ->
            button.isAllCaps = false
            button.setTextColor(accent(context))
            button.background = secondary(context, radiusDp = 12)
            button.setPadding(dp(context, 14), 0, dp(context, 14), 0)
        }
    }

    private fun rounded(
        color: Int,
        radiusPx: Int,
        strokeColor: Int,
        strokeWidthPx: Int,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(color)
        if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
    }
}
