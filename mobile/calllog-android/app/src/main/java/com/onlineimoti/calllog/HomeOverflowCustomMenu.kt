package com.onlineimoti.calllog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.min

internal object HomeOverflowCustomMenu {
    fun show(activity: AppCompatActivity, anchor: View, openSettings: () -> Unit) {
        val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }
        val popupWidth = min(dp(360), activity.resources.displayMetrics.widthPixels - dp(24))
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = rounded(Color.WHITE, dp(10).toFloat())
            elevation = dp(8).toFloat()
        }
        val popup = PopupWindow(root, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(rounded(Color.WHITE, dp(10).toFloat()))
        }

        val localDeviceActions = DistributionCapabilities.supportsLocalDeviceData
        val contactsMode = HomeCrmTimelineModeToggle.isContactsMode()

        fun addMainRow(iconRes: Int, title: String, onClick: () -> Unit) {
            root.addView(menuRow(activity, dp, iconRes, title).apply {
                setOnClickListener {
                    popup.dismiss()
                    onClick()
                }
            })
        }

        if (localDeviceActions && !contactsMode) {
            addMainRow(R.drawable.ic_menu_call_history, activity.getString(R.string.home_overflow_phone_log)) {
                activity.startActivity(
                    Intent(activity, SystemCallHistoryActivity::class.java)
                        .putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_GENERAL),
                )
            }
        }
        if (localDeviceActions) {
            addMainRow(R.drawable.ic_menu_new_contact, activity.getString(R.string.home_overflow_new_contact)) {
                openNewContact(activity)
            }
            addMainRow(R.drawable.ic_menu_contacts, activity.getString(R.string.runtime_menu_phone_contacts)) {
                openDefaultContacts(activity)
            }

            val favorites = loadFavoriteContacts(activity)
            val favoritesBlock = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(8))
                background = rounded(Color.rgb(246, 247, 252), dp(14).toFloat())
            }
            favoritesBlock.addView(menuRow(activity, dp, R.drawable.ic_menu_favorite, activity.getString(R.string.home_overflow_favorites)).apply {
                setOnClickListener {
                    popup.dismiss()
                    openFavoriteContacts(activity)
                }
            })
            favorites.forEach { favorite ->
                favoritesBlock.addView(favoriteRow(activity, dp, favorite).apply {
                    setOnClickListener {
                        popup.dismiss()
                        openDialer(activity, favorite.phone)
                    }
                })
            }
            root.addView(
                favoritesBlock,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(4)
                },
            )

            addMainRow(R.drawable.ic_menu_sms, activity.getString(R.string.runtime_menu_sms)) {
                activity.startActivity(Intent(activity, SmsHistoryActivity::class.java))
            }
            addMainRow(R.drawable.ic_menu_calendar, activity.getString(R.string.runtime_menu_calendar)) {
                openDefaultCalendar(activity)
            }
        }
        addMainRow(R.drawable.ic_menu_settings, activity.getString(R.string.home_overflow_settings)) {
            openSettings()
        }

        root.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        popup.showAsDropDown(anchor, -(popupWidth - anchor.width), -anchor.height)
    }

    private fun menuRow(
        activity: AppCompatActivity,
        dp: (Int) -> Int,
        iconRes: Int,
        title: String,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(4))
        minimumHeight = dp(54)
        isClickable = true
        isFocusable = true
        background = selectableBackground(activity)

        addView(ImageView(activity).apply {
            setImageResource(iconRes)
            imageTintList = ContextCompat.getColorStateList(activity, R.color.home_menu_icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(30), dp(30)))

        addView(TextView(activity).apply {
            text = title
            textSize = 18f
            setTextColor(Color.rgb(39, 39, 39))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(12)
        })
    }

    private fun favoriteRow(
        activity: AppCompatActivity,
        dp: (Int) -> Int,
        favorite: FavoriteContact,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(30), dp(3), dp(8), dp(3))
        minimumHeight = dp(48)
        isClickable = true
        isFocusable = true
        background = selectableBackground(activity)

        val avatarBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(229, 232, 238))
        }
        addView(ImageView(activity).apply {
            background = avatarBackground
            setImageDrawable(favorite.photo ?: ContextCompat.getDrawable(activity, R.drawable.ic_menu_favorite))
            scaleType = if (favorite.photo != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER_INSIDE
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            if (favorite.photo == null) setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(38), dp(38)))

        addView(TextView(activity).apply {
            text = favorite.name
            textSize = 17f
            setTextColor(Color.rgb(39, 39, 39))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            isSingleLine = true
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(14)
        })
    }

    private fun selectableBackground(activity: AppCompatActivity): Drawable? {
        val attrs = intArrayOf(android.R.attr.selectableItemBackground)
        return activity.obtainStyledAttributes(attrs).let { typedArray ->
            try {
                typedArray.getDrawable(0)
            } finally {
                typedArray.recycle()
            }
        }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    private fun loadFavoriteContacts(activity: AppCompatActivity): List<FavoriteContact> {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val favorites = mutableListOf<FavoriteContact>()
        runCatching {
            activity.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ),
                "${ContactsContract.Contacts.STARRED}=1 AND ${ContactsContract.Contacts.HAS_PHONE_NUMBER}>0",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val photoIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idIndex)
                    val phone = preferredPhone(activity, contactId)
                    if (phone.isBlank()) continue
                    val name = cursor.getString(nameIndex).orEmpty().trim().ifBlank { phone }
                    favorites += FavoriteContact(name, phone, loadContactPhoto(activity, cursor.getString(photoIndex)))
                }
            }
        }
        return favorites
    }

    private fun loadContactPhoto(activity: AppCompatActivity, thumbnailUri: String?): Drawable? {
        if (thumbnailUri.isNullOrBlank()) return null
        return runCatching {
            activity.contentResolver.openInputStream(Uri.parse(thumbnailUri))?.use { stream ->
                Drawable.createFromStream(stream, "favorite_contact")
            }
        }.getOrNull()
    }

    private fun preferredPhone(activity: AppCompatActivity, contactId: Long): String = runCatching {
        activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            "${ContactsContract.Data.IS_SUPER_PRIMARY} DESC, ${ContactsContract.Data.IS_PRIMARY} DESC",
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIndex).orEmpty().trim()
                if (number.isNotBlank()) return@use number
            }
            ""
        }.orEmpty()
    }.getOrDefault("")

    private fun openDialer(activity: AppCompatActivity, phone: String) {
        tryStart(activity, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
    }

    private fun openNewContact(activity: AppCompatActivity) {
        val insertIntent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
        if (!tryStart(activity, insertIntent)) openDefaultContacts(activity)
    }

    private fun openDefaultContacts(activity: AppCompatActivity) {
        val contactsIntent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        val fallbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)
        runCatching { activity.startActivity(contactsIntent) }
            .recoverCatching { activity.startActivity(fallbackIntent) }
    }

    private fun openFavoriteContacts(activity: AppCompatActivity) {
        val favoritesIntent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply {
            putExtra("android.provider.extra.STARRED_ONLY", true)
        }
        val starredUriIntent = Intent(
            Intent.ACTION_VIEW,
            ContactsContract.Contacts.CONTENT_URI.buildUpon().appendQueryParameter("starred", "1").build(),
        )
        if (!tryStart(activity, favoritesIntent) && !tryStart(activity, starredUriIntent)) {
            openDefaultContacts(activity)
        }
    }

    private fun openDefaultCalendar(activity: AppCompatActivity) {
        val todayIntent = Intent(Intent.ACTION_VIEW).setData(
            CalendarContract.CONTENT_URI.buildUpon()
                .appendPath("time")
                .appendPath(System.currentTimeMillis().toString())
                .build(),
        )
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
        val legacyIntent = Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI)
        if (!tryStart(activity, todayIntent) && !tryStart(activity, launcherIntent) && !tryStart(activity, legacyIntent)) {
            Toast.makeText(activity, activity.getString(R.string.runtime_calendar_app_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryStart(activity: AppCompatActivity, intent: Intent): Boolean = runCatching {
        activity.startActivity(intent)
        true
    }.getOrDefault(false)

    private data class FavoriteContact(
        val name: String,
        val phone: String,
        val photo: Drawable?,
    )
}
