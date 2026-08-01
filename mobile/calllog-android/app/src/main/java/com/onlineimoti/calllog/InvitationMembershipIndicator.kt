package com.onlineimoti.calllog

import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Informational marker for invitations targeting an existing active company member. */
internal object InvitationMembershipIndicator {
    fun add(
        activity: AppCompatActivity,
        row: LinearLayout,
        invitation: InvitationCenterApi.Invitation,
    ) {
        if (!invitation.alreadyMember) return
        val role = roleLabel(invitation.currentRole)
        row.addView(TextView(activity).apply {
            text = buildString {
                append("✓ Вече е член")
                if (role.isNotBlank()) append(" · ").append(role)
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.calllog_accent))
            setPadding(dp(activity, 9), dp(activity, 5), dp(activity, 9), dp(activity, 5))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(activity, R.color.calllog_debug_surface))
                setStroke(dp(activity, 1), ContextCompat.getColor(activity, R.color.calllog_accent))
                cornerRadius = dp(activity, 12).toFloat()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(activity, 7) })
    }

    private fun roleLabel(role: String): String = when (role.lowercase()) {
        "owner" -> "Собственик"
        "admin" -> "Администратор"
        "member" -> "Член"
        "broker" -> "Брокер"
        else -> ""
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
