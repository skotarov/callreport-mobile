package com.onlineimoti.calllog

internal data class HomeSyncStatusState(
    val visible: Boolean,
    val badgeText: String,
    val hasIssue: Boolean,
)

internal fun homeSyncStatusState(pendingCount: Int, hasIssue: Boolean = false): HomeSyncStatusState {
    val count = pendingCount.coerceAtLeast(0)
    if (count == 0) return HomeSyncStatusState(visible = false, badgeText = "", hasIssue = false)
    return HomeSyncStatusState(
        visible = true,
        badgeText = if (count > 99) "99+" else count.toString(),
        hasIssue = hasIssue,
    )
}
