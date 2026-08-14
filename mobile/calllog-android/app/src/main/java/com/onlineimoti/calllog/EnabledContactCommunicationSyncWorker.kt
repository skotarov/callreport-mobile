package com.onlineimoti.calllog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EnabledContactCommunicationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = ConfigStore.load(applicationContext)
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            return@withContext Result.success()
        }

        // The acknowledgement index is a cache, not source data; trim legacy growth first.
        ServerRecordIndex.prune(applicationContext)

        val events = buildList {
            CallReportProviderEventReader.recentPhoneEvents(applicationContext, CALL_SYNC_LIMIT)
                .filter {
                    CommunicationSyncPrivacyPolicy.shouldShareCall(
                        applicationContext,
                        it.phone,
                        it.direction,
                        it.occurredAtMs,
                    )
                }
                .mapNotNullTo(this) { CallReportSyncEventFactory.fromPhoneCall(applicationContext, it) }
            CallReportProviderEventReader.recentSmsEvents(applicationContext, SMS_SYNC_LIMIT)
                .filter { CommunicationSyncPrivacyPolicy.shouldShare(applicationContext, it.phone) }
                .mapNotNullTo(this) { CallReportSyncEventFactory.fromSms(applicationContext, it) }
        }.distinctBy { it.clientEventId }.sortedByDescending { it.occurredAtMs }

        try {
            events.chunked(MAX_BATCH_SIZE).forEach { candidates ->
                // Re-check immediately before upload because the user may have added
                // a number to Contacts or changed the CRM/care marker meanwhile.
                val batch = candidates.filter { event ->
                    if (event.communicationType == "phone") {
                        CommunicationSyncPrivacyPolicy.shouldShareCall(
                            applicationContext,
                            event.phone,
                            event.direction,
                            event.occurredAtMs,
                        )
                    } else {
                        CommunicationSyncPrivacyPolicy.shouldShare(applicationContext, event.phone)
                    }
                }
                if (batch.isNotEmpty()) {
                    val confirmed = CallReportSyncClient.sync(config, batch)
                    ServerRecordIndex.markConfirmed(applicationContext, confirmed)
                    batch.asSequence()
                        .filter { it.communicationType == "phone" && it.clientEventId in confirmed }
                        .forEach { event ->
                            CompanySharedCallStore.clear(
                                applicationContext,
                                event.phone,
                                event.direction,
                                event.occurredAtMs,
                            )
                        }
                }
            }
            Result.success()
        } catch (error: CallReportSyncException) {
            if (error.retryable && runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        } catch (_: Throwable) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val CALL_SYNC_LIMIT = 100
        const val SMS_SYNC_LIMIT = 100
        const val MAX_BATCH_SIZE = 50
        const val MAX_RETRY_ATTEMPTS = 5
    }
}
