package com.onlineimoti.calllog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat

/** Keeps HomeActivity focused on state coordination rather than menu plumbing. */
internal object HomeOverflowMenu {
    fun show(activity: AppCompatActivity, anchor: View, openSettings: () -> Unit) {
        val favoritePhonesByMenuId = linkedMapOf<Int, String>()
        PopupMenu(activity, anchor).apply {
            // AppCompat allows consistently visible menu icons across Android skins.
            setForceShowIcon(true)
            val localDeviceActions = DistributionCapabilities.supportsLocalDeviceData
            val contactsMode = HomeCrmTimelineModeToggle.isContactsMode()
            if (localDeviceActions && !contactsMode) {
                menu.add(0, MENU_PHONE_CALL_LOG, 10, activity.getString(R.string.home_overflow_phone_log))
                    .setIcon(R.drawable.ic_menu_call_history)
            }
            if (localDeviceActions) {
                menu.add(0, MENU_NEW_CONTACT, 25, activity.getString(R.string.home_overflow_new_contact))
                    .setIcon(R.drawable.ic_menu_new_contact)
                menu.add(0, MENU_PHONE_CONTACTS, 30, activity.getString(R.string.runtime_menu_phone_contacts))
                    .setIcon(R.drawable.ic_menu_contacts)
                menu.add(0, MENU_FAVORITE_CONTACTS, 35, activity.getString(R.string.home_overflow_favorites))
                    .setIcon(R.drawable.ic_menu_favorite)
                loadFavoriteContacts(activity).forEachIndexed { index, favorite ->
                    val itemId = MENU_FAVORITE_CONTACT_BASE + index
                    favoritePhonesByMenuId[itemId] = favorite.phone
                    val title = SpannableString("\u2003${favorite.name}").apply {
                        setSpan(
                            BackgroundColorSpan(FAVORITE_CONTACT_BACKGROUND),
                            0,
                            length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    val menuItem = menu.add(
                        0,
                        itemId,
                        FAVORITE_CONTACT_ORDER_BASE + index,
                        title,
                    )
                    menuItem.icon = favorite.photo ?: ContextCompat.getDrawable(activity, R.drawable.ic_menu_favorite)
                }
                menu.add(0, MENU_SMS, 1_000, activity.getString(R.string.runtime_menu_sms))
                    .setIcon(R.drawable.ic_menu_sms)
                menu.add(0, MENU_CALENDAR, 1_010, activity.getString(R.string.runtime_menu_calendar))
                    .setIcon(R.drawable.ic_menu_calendar)
            }
            menu.add(0, MENU_SETTINGS, 1_020, activity.getString(R.string.home_overflow_settings))
                .setIcon(R.drawable.ic_menu_settings)
            setOnMenuItemClickListener { item ->
                favoritePhonesByMenuId[item.itemId]?.let { phone ->
                    openDialer(activity, phone)
                    return@setOnMenuItemClickListener true
                }
                when (item.itemId) {
                    MENU_PHONE_CALL_LOG -> {
                        activity.startActivity(
                            Intent(activity, SystemCallHistoryActivity::class.java)
                                .putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_GENERAL),
                        )
                        true
                    }
                    MENU_NEW_CONTACT -> {
                        openNewContact(activity)
                        true
                    }
                    MENU_PHONE_CONTACTS -> {
                        openDefaultContacts(activity)
                        true
                    }
                    MENU_FAVORITE_CONTACTS -> {
                        openFavoriteContacts(activity)
                        true
                    }
                    MENU_SMS -> {
                        activity.startActivity(Intent(activity, SmsHistoryActivity::class.java))
                        true
                    }
                    MENU_CALENDAR -> {
                        openDefaultCalendar(activity)
                        true
                    }
                    MENU_SETTINGS -> {
                        openSettings()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
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
                    val photo = loadContactPhoto(activity, cursor.getString(photoIndex))
                    favorites += FavoriteContact(name = name, phone = phone, photo = photo)
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
        if (!tryStart(activity, insertIntent)) {
            openDefaultContacts(activity)
        }
    }

    private fun openDefaultContacts(activity: AppCompatActivity) {
        val contactsIntent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        val fallbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)
        runCatching { activity.startActivity(contactsIntent) }
            .recoverCatching { activity.startActivity(fallbackIntent) }
    }

    /**
     * Asks the installed/default Contacts app for its starred contacts view.
     * ContactsContract exposes starred contacts through the standard Contacts URI selection;
     * OEM contact apps that support this intent can render it as their Favorites section.
     */
    private fun openFavoriteContacts(activity: AppCompatActivity) {
        val favoritesIntent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply {
            putExtra("android.provider.extra.STARRED_ONLY", true)
        }
        val starredUriIntent = Intent(
            Intent.ACTION_VIEW,
            ContactsContract.Contacts.CONTENT_URI.buildUpon()
                .appendQueryParameter("starred", "1")
                .build(),
        )
        if (!tryStart(activity, favoritesIntent) && !tryStart(activity, starredUriIntent)) {
            openDefaultContacts(activity)
        }
    }

    /** Opens the system/default calendar directly on today's date. */
    private fun openDefaultCalendar(activity: AppCompatActivity) {
        val todayIntent = Intent(Intent.ACTION_VIEW).setData(
            CalendarContract.CONTENT_URI.buildUpon()
                .appendPath("time")
                .appendPath(System.currentTimeMillis().toString())
                .build(),
        )
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
        val legacyIntent = Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI)
        val opened = tryStart(activity, todayIntent) ||
            tryStart(activity, launcherIntent) ||
            tryStart(activity, legacyIntent)
        if (!opened) {
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

    private const val MENU_PHONE_CALL_LOG = 1
    private const val MENU_PHONE_CONTACTS = 3
    private const val MENU_SMS = 4
    private const val MENU_CALENDAR = 5
    private const val MENU_SETTINGS = 6
    private const val MENU_NEW_CONTACT = 7
    private const val MENU_FAVORITE_CONTACTS = 8
    private const val MENU_FAVORITE_CONTACT_BASE = 10_000
    private const val FAVORITE_CONTACT_ORDER_BASE = 100
    private val FAVORITE_CONTACT_BACKGROUND = Color.argb(18, 128, 128, 128)
}
