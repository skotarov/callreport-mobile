package com.onlineimoti.calllog

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.ContactsContract
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Avatar used by the expanded History contact identity. */
internal class ContactHeaderIdentityAvatarUi(
    private val activity: ContactNotesActivity,
    private val dp: (Int) -> Int,
) {
    fun create(
        phone: String,
        contactExists: Boolean,
        serverBacked: Boolean,
    ): FrameLayout {
        val size = dp(AVATAR_SIZE_DP)
        return FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = ViewGroup.LayoutParams(size, size)
            contentDescription = when {
                !contactExists -> if (AppLocaleText.isBulgarian()) "Непознат номер" else "Unknown number"
                else -> if (AppLocaleText.isBulgarian()) "Снимка на контакт" else "Contact photo"
            }

            addView(
                if (contactExists) savedContactAvatar(phone) else unknownAvatar(),
                FrameLayout.LayoutParams(size, size, Gravity.CENTER),
            )
            if (serverBacked) addView(serverBadge(), serverBadgeLayoutParams())
        }
    }

    private fun savedContactAvatar(phone: String): ImageView = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = oval(Color.rgb(226, 232, 240))
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setImageResource(R.drawable.ic_contact_person)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(100, 116, 139))

        loadContactPhoto(phone)?.let { bitmap ->
            setPadding(0, 0, 0, 0)
            imageTintList = null
            setImageBitmap(bitmap)
        }
    }

    private fun unknownAvatar(): TextView = TextView(activity).apply {
        text = "?"
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(100, 116, 139))
        background = oval(Color.rgb(226, 232, 240))
    }

    private fun serverBadge(): ImageView = ImageView(activity).apply {
        setImageResource(R.drawable.ic_cloud_note_filled)
        imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(activity, R.color.callreport_icon_background),
        )
        background = oval(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(4), dp(4), dp(4), dp(4))
        elevation = dp(2).toFloat()
        contentDescription = if (AppLocaleText.isBulgarian()) "Има сървърен запис" else "Server record exists"
    }

    private fun serverBadgeLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        dp(SERVER_BADGE_SIZE_DP),
        dp(SERVER_BADGE_SIZE_DP),
        Gravity.END or Gravity.BOTTOM,
    ).apply {
        marginEnd = -dp(2)
        bottomMargin = -dp(2)
    }

    private fun loadContactPhoto(phone: String): android.graphics.Bitmap? {
        if (phone.isBlank()) return null
        if (
            ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val contactId = runCatching { RmRealContactLookup.findContactId(activity, phone) }.getOrDefault(0L)
        if (contactId <= 0L) return null
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        return runCatching {
            ContactsContract.Contacts.openContactPhotoInputStream(
                activity.contentResolver,
                uri,
                true,
            )?.use(BitmapFactory::decodeStream)
        }.getOrNull()
    }

    private fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private companion object {
        const val AVATAR_SIZE_DP = 76
        const val SERVER_BADGE_SIZE_DP = 26
    }
}
