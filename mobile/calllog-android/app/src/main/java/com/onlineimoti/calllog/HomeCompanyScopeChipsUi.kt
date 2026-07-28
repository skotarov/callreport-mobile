package com.onlineimoti.calllog

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Compact Home row for CRM status and company scopes. */
internal class HomeCompanyScopeChipsUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun create(
        labels: List<HomeCompanyScopeLabel>?,
        crmClient: Boolean,
        onClick: (() -> Unit)? = null,
        showCrmLabel: Boolean = true,
        showPhaseDots: Boolean = true,
        serverBacked: Boolean = false,
    ): HorizontalScrollView {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            bindHistoryClick(onClick, "Отвори историята на контакта")
        }
        var hasPrevious = false
        when {
            crmClient && showCrmLabel -> {
                row.addView(crmLabel(onClick))
                hasPrevious = true
            }
            serverBacked && showCrmLabel -> {
                row.addView(cloudLabel(onClick))
                hasPrevious = true
            }
        }
        labels.orEmpty().forEach { label ->
            row.addView(chip(label, onClick, showPhaseDots), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (hasPrevious) marginStart = dp(6)
            })
            hasPrevious = true
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
            bindHistoryClick(onClick, "Отвори историята на контакта")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        }
    }

    /** CRM/cloud and all available company phase dots are placed directly before the name or phone. */
    fun inlineCrmIdentity(
        identity: CharSequence,
        labels: List<HomeCompanyScopeLabel>?,
        crmClient: Boolean,
        serverBacked: Boolean = false,
    ): CharSequence {
        if (!crmClient && !serverBacked) return identity
        val builder = SpannableStringBuilder()
        if (crmClient) appendCrmPrefix(builder) else appendCloudPrefix(builder)
        labels.orEmpty()
            .filter { it.phase in 1..4 }
            .forEach { label ->
                builder.append(" ")
                val dotStart = builder.length
                builder.append("●")
                builder.setSpan(
                    ForegroundColorSpan(phaseColor(label.phase)),
                    dotStart,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        builder.append(" ")
        builder.append(identity)
        return builder
    }

    private fun appendCrmPrefix(builder: SpannableStringBuilder) {
        val start = builder.length
        val drawable = ContextCompat.getDrawable(activity, R.drawable.ic_client_care)?.mutate()
        if (drawable == null) {
            builder.append("CRM")
            builder.setSpan(
                ForegroundColorSpan(activity.getColor(R.color.callreport_icon_background)),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            return
        }
        drawable.setTint(activity.getColor(R.color.callreport_icon_background))
        drawable.setBounds(0, 0, dp(20), dp(20))
        builder.append("\uFFFC")
        builder.setSpan(
            ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun appendCloudPrefix(builder: SpannableStringBuilder) {
        val start = builder.length
        val drawable = ContextCompat.getDrawable(activity, R.drawable.ic_cloud_note_filled)?.mutate()
        if (drawable == null) {
            builder.append("☁")
            builder.setSpan(
                ForegroundColorSpan(activity.getColor(R.color.callreport_icon_background)),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            return
        }
        drawable.setTint(activity.getColor(R.color.callreport_icon_background))
        drawable.setBounds(0, 0, dp(18), dp(18))
        builder.append("\uFFFC")
        builder.setSpan(
            ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun crmLabel(onClick: (() -> Unit)?): TextView = TextView(activity).apply {
        text = "CRM"
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(7), dp(4), dp(7), dp(4))
        background = roundedRect(
            activity.getColor(R.color.callreport_icon_background),
            dp(9),
            Color.TRANSPARENT,
            0,
        )
        bindHistoryClick(onClick, "CRM. Отвори историята на контакта")
    }

    private fun cloudLabel(onClick: (() -> Unit)?): ImageView = ImageView(activity).apply {
        setImageResource(R.drawable.ic_cloud_note_filled)
        imageTintList = ColorStateList.valueOf(activity.getColor(R.color.callreport_icon_background))
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(4), dp(3), dp(4), dp(3))
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(28))
        bindHistoryClick(onClick, "Има сървърна история. Отвори историята на контакта")
    }

    private fun chip(
        label: HomeCompanyScopeLabel,
        onClick: (() -> Unit)?,
        showPhaseDot: Boolean,
    ): TextView {
        val hasPhase = showPhaseDot && label.phase in 1..4
        val colors = if (label.hasGeneralNote) {
            ChipColors(
                background = NoteUiStyle.General.background,
                border = NoteUiStyle.General.border,
                text = NoteUiStyle.General.text,
            )
        } else {
            GrayChipColors
        }
        val textValue = buildString {
            append("[ ")
            if (hasPhase) append("● ")
            append(label.companyName)
            append(" ]")
        }
        val styledText = SpannableString(textValue).apply {
            if (hasPhase) {
                val dotIndex = textValue.indexOf('●')
                if (dotIndex >= 0) setSpan(
                    ForegroundColorSpan(phaseColor(label.phase)),
                    dotIndex,
                    dotIndex + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return TextView(activity).apply {
            text = styledText
            setTextColor(colors.text)
            textSize = 12f
            maxLines = 1
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = roundedRect(colors.background, dp(9), colors.border, dp(1))
            bindHistoryClick(onClick, "${label.companyName}. Отвори историята на контакта")
        }
    }

    private fun View.bindHistoryClick(onClick: (() -> Unit)?, description: String) {
        if (onClick == null) return
        isClickable = true
        isFocusable = true
        contentDescription = description
        setOnClickListener { onClick() }
    }

    private fun phaseColor(phase: Int): Int = when (phase) {
        1 -> Color.rgb(22, 163, 74)
        2 -> Color.rgb(37, 99, 235)
        3 -> Color.rgb(250, 204, 21)
        4 -> Color.rgb(220, 38, 38)
        else -> GrayChipColors.text
    }

    private data class ChipColors(
        val background: Int,
        val border: Int,
        val text: Int,
    )

    private val GrayChipColors = ChipColors(
        background = Color.rgb(241, 245, 249),
        border = Color.TRANSPARENT,
        text = Color.rgb(71, 85, 105),
    )
}
