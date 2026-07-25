package com.onlineimoti.calllog

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import android.os.Process

class CallReportSyncService : Service() {
    private val syncAdapter by lazy { CallReportSyncAdapter(this) }
    override fun onBind(intent: Intent?): IBinder = syncAdapter.syncAdapterBinder
}

private class CallReportSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {
    override fun onPerformSync(
        account: Account?,
        extras: Bundle?,
        authority: String?,
        provider: ContentProviderClient?,
        syncResult: SyncResult?,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        if (authority != android.provider.ContactsContract.AUTHORITY) return
        if (account?.type != CallReportContactIntegration.ACCOUNT_TYPE) return

        val result = BulkContactsTaskRunner.registerAllFromSync(context) ?: return

        // Reuse the normal Android/server synchronization point to refresh the
        // account-scoped list of firms the authenticated user may assign notes to.
        // A temporary network failure keeps the previous verified cache intact.
        val config = ConfigStore.load(context.applicationContext)
        if (CallReportRemoteAccess.isReady(config)) {
            runCatching {
                CallReportTopicCompaniesRepository.refresh(context.applicationContext, config)
            }
        }

        syncResult?.stats?.numEntries = result.scanned.toLong()
        syncResult?.stats?.numInserts = result.created.toLong()
        syncResult?.stats?.numUpdates = result.skippedExisting.toLong()
        syncResult?.stats?.numSkippedEntries = result.failed.toLong()
    }
}
