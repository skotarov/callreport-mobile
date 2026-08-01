package com.onlineimoti.calllog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends durable profile/company edits after Android reports a working network. */
class AccountMutationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            while (true) {
                val batch = AccountMutationOutbox.takeCurrentBatch(applicationContext)
                if (batch.isEmpty()) break

                batch.forEach { operation ->
                    // Never send an operation through a different signed-in profile.
                    // The operation remains durable and will be scheduled again when
                    // its original profile becomes active.
                    if (operation.accountScope != OfflineAccountScope.current(applicationContext)) {
                        return@withContext Result.success()
                    }
                    val operationConfig = ConfigStore.load(applicationContext)
                    if (operationConfig.baseUrl.isBlank() || operationConfig.accessToken.isBlank()) {
                        return@withContext Result.success()
                    }

                    when (operation.kind) {
                        AccountMutationOutbox.Kind.PROFILE_NAME -> {
                            val serverUser = ProfileNameApi.updateRemote(
                                operationConfig,
                                operation.name,
                            ).getOrThrow()
                            val removed = AccountMutationOutbox.acknowledgeConfirmed(
                                applicationContext,
                                operation,
                            )
                            // Do not overwrite a newer local edit that was queued while
                            // this network request was in flight.
                            if (removed) {
                                CompanyAccountApi.applyProfileUser(applicationContext, serverUser)
                            }
                        }

                        AccountMutationOutbox.Kind.COMPANY_UPDATE -> {
                            CompanyManagementApi.update(
                                config = operationConfig,
                                companyId = operation.companyId,
                                name = operation.name,
                                eik = operation.eik,
                            )
                            AccountMutationOutbox.acknowledgeConfirmed(
                                applicationContext,
                                operation,
                            )
                        }
                    }
                    AccountMutationOutbox.clearFailure(applicationContext)
                }
            }
            Result.success()
        } catch (error: Throwable) {
            AccountMutationOutbox.recordFailure(
                applicationContext,
                error.message?.takeIf(String::isNotBlank)
                    ?: "Промяната чака повторна синхронизация.",
            )
            Result.retry()
        }
    }
}
