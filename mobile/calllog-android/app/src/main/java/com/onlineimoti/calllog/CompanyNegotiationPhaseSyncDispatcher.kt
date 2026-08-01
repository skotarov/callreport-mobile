package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs company phase reconciliation away from the UI thread. */
internal object CompanyNegotiationPhaseSyncDispatcher {
    private const val WORK_PREFIX = "relationship_manager_company_phase_sync"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun synchronize(context: Context, phone: String, companyId: String, onResolved: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        val safePhone = phone.trim()
        val safeCompanyId = companyId.trim()
        val accountScope = OfflineAccountScope.current(appContext)
        if (safePhone.isBlank() || safeCompanyId.isBlank() || accountScope.isBlank()) return

        val uniqueWorkName = workName(safePhone, safeCompanyId, accountScope)
        val request = OneTimeWorkRequestBuilder<CompanyNegotiationPhaseSyncWorker>()
            .setInputData(
                workDataOf(
                    CompanyNegotiationPhaseSyncWorker.KEY_PHONE to safePhone,
                    CompanyNegotiationPhaseSyncWorker.KEY_COMPANY_ID to safeCompanyId,
                    CompanyNegotiationPhaseSyncWorker.KEY_ACCOUNT_SCOPE to accountScope,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            // This is a durable backup for the immediate online path. A short delay
            // prevents the backup worker from racing the request already in progress.
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.REPLACE,
            request,
        )

        // The persistent worker is enough while offline. When already online, keep
        // the existing immediate UI refresh and cancel the delayed backup on success.
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(appContext))) return
        val activity = context as? Activity
        val busyToken = activity?.let { HomeBusyTooltipUi.begin(it, HomeBusyWork.COMPANY_DATA) } ?: 0L
        executor.execute {
            val result = runCatching {
                CompanyNegotiationPhaseSync.synchronize(appContext, safePhone, safeCompanyId)
            }
            if (result.isSuccess) {
                WorkManager.getInstance(appContext).cancelUniqueWork(uniqueWorkName)
            }
            mainHandler.post {
                if (activity != null) HomeBusyTooltipUi.end(activity, busyToken)
                onResolved(result.getOrDefault(false))
            }
        }
    }

    private fun workName(phone: String, companyId: String, accountScope: String): String {
        val phoneKey = HomeCallPageLoader.noteKey(phone).ifBlank { phone.trim() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$accountScope|$phoneKey|$companyId".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "$WORK_PREFIX:$digest"
    }
}
