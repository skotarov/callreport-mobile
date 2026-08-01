package com.onlineimoti.calllog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Retries one company-scoped phase reconciliation when connectivity returns. */
class CompanyNegotiationPhaseSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val phone = inputData.getString(KEY_PHONE).orEmpty().trim()
        val companyId = inputData.getString(KEY_COMPANY_ID).orEmpty().trim()
        val accountScope = inputData.getString(KEY_ACCOUNT_SCOPE).orEmpty().trim()
        if (phone.isBlank() || companyId.isBlank() || accountScope.isBlank()) {
            return@withContext Result.failure()
        }

        // Keep the work, but never send it through another signed-in profile.
        if (OfflineAccountScope.current(applicationContext) != accountScope) {
            return@withContext Result.retry()
        }
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(applicationContext))) {
            return@withContext Result.retry()
        }

        runCatching {
            CompanyNegotiationPhaseSync.synchronize(
                applicationContext,
                phone,
                companyId,
            )
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val KEY_PHONE = "phase_sync_phone"
        const val KEY_COMPANY_ID = "phase_sync_company_id"
        const val KEY_ACCOUNT_SCOPE = "phase_sync_account_scope"
    }
}
