package com.onlineimoti.calllog

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.button.MaterialButton

/** Secondary History action row reserved for Viber and future chat applications. */
internal class ContactNotesChatActionsUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun row(phone: String): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(2), dp(12), dp(4))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(viberButton(phone))
    }

    private fun viberButton(phone: String): MaterialButton =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Viber"
            contentDescription = if (AppLocaleText.isBulgarian()) {
                "Отвори профила или чата във Viber"
            } else {
                "Open the profile or chat in Viber"
            }
            isAllCaps = false
            textSize = 13f
            minimumWidth = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            setPadding(dp(12), 0, dp(14), 0)
            icon = activity.getDrawable(R.drawable.ic_chat_viber)
            iconTint = ColorStateList.valueOf(VIBER_PURPLE)
            iconSize = dp(21)
            iconPadding = dp(7)
            setTextColor(VIBER_PURPLE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(250, 248, 255))
            strokeColor = ColorStateList.valueOf(Color.rgb(203, 193, 229))
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40),
            )
            setOnClickListener { openViber(phone) }
        }

    private fun openViber(phone: String) {
        val normalized = PhoneNormalizer.normalize(phone)
        if (normalized.isBlank()) {
            toast(if (AppLocaleText.isBulgarian()) "Невалиден телефонен номер" else "Invalid phone number")
            return
        }

        val chatIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("viber://chat?number=${Uri.encode(normalized)}"),
        ).setPackage(VIBER_PACKAGE)
        if (start(chatIntent)) return

        val addIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("viber://add?number=${normalized.filter(Char::isDigit)}"),
        ).setPackage(VIBER_PACKAGE)
        if (start(addIntent)) return

        toast(if (AppLocaleText.isBulgarian()) "Viber не е инсталиран" else "Viber is not installed")
    }

    private fun start(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val VIBER_PACKAGE = "com.viber.voip"
        val VIBER_PURPLE: Int = Color.rgb(102, 92, 172)
    }
}
