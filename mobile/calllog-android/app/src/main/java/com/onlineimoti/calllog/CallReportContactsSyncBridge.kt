package com.onlineimoti.calllog

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object CallReportContactsSyncBridge {
    private const val PREFS = "callreport_contacts_sync_bridge"
    private const val KEY_LAST_SYNC_REQUEST_MS = "last_sync_request_ms"
    private const val KEY_LINKED_APP_REPAIR_VERSION = "linked_app_repair_version"
    private const val LINKED_APP_REPAIR_VERSION = 1
    private const val SYNC_REQUEST_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val PERIODIC_SYNC_SECONDS = 6L * 60L * 60L

    fun ensureAccountAndRequestSync(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        CrmContactAccountStore.ensureAccount(appContext, syncAutomatically = true)
        val account = Account(CrmContactAccountStore.ACCOUNT_NAME, CallReportContactIntegration.ACCOUNT_TYPE)
        ensurePeriodicSync(account)

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val needsLinkedAppRepair =
            prefs.getInt(KEY_LINKED_APP_REPAIR_VERSION, 0) < LINKED_APP_REPAIR_VERSION
        if (!force && !needsLinkedAppRepair) return
        if (!canReadAndWriteContacts(appContext)) return

        val now = System.currentTimeMillis()
        val lastRequest = prefs.getLong(KEY_LAST_SYNC_REQUEST_MS, 0L)
        // A metadata repair must run immediately even if an ordinary sync was
        // requested recently by the previous, incorrectly registered build.
        if (!needsLinkedAppRepair && now - lastRequest < SYNC_REQUEST_INTERVAL_MS) return

        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }

        runCatching {
            ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
            prefs.edit()
                .putLong(KEY_LAST_SYNC_REQUEST_MS, now)
                .putInt(KEY_LINKED_APP_REPAIR_VERSION, LINKED_APP_REPAIR_VERSION)
                .apply()
        }
    }

    private fun ensurePeriodicSync(account: Account) {
        runCatching {
            val extras = Bundle.EMPTY
            val alreadyAdded = ContentResolver.getPeriodicSyncs(account, ContactsContract.AUTHORITY).any { sync ->
                sync.period == PERIODIC_SYNC_SECONDS
            }
            if (!alreadyAdded) {
                ContentResolver.addPeriodicSync(account, ContactsContract.AUTHORITY, extras, PERIODIC_SYNC_SECONDS)
            }
        }
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}
