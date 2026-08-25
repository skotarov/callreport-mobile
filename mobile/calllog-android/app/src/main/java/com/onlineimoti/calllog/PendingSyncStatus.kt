package com.onlineimoti.calllog

import android.content.Context

/** The durable local changes that still await server acknowledgement. */
internal data class PendingSyncSummary(
    val count: Int = 0,
    val failure: String = "",
)

internal object PendingSyncStatus {
    fun summary(context: Context): PendingSyncSummary {
        val count = CallReportNoteOutbox.pendingCount(context) +
            CallReportTopicNoteOutbox.pendingCount(context) +
            CompanyCallNoteOutbox.pendingClientEventIds(context).size +
            AccountMutationOutbox.pendingCountForCurrentAccount(context)
        if (count == 0) return PendingSyncSummary()

        val failure = listOf(
            CallReportNoteOutbox.lastFailure(context),
            CallReportTopicNoteOutbox.lastFailure(context),
            CompanyCallNoteOutbox.lastFailure(context),
            AccountMutationOutbox.lastFailure(context),
        ).firstOrNull(String::isNotBlank).orEmpty()
        return PendingSyncSummary(count, failure)
    }
}
