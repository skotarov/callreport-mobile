package com.onlineimoti.calllog

internal data class HomeSyncStatusState(
    val visible: Boolean,
    val badgeText: String,
)

internal fun homeSyncStatusState(pendingCount: Int): HomeSyncStatusState {
    val count = pendingCount.coerceAtLeast(0)
    if (count == 0) return HomeSyncStatusState(visible = false, badgeText = "")
    return HomeSyncStatusState(
        visible = true,
        badgeText = if (count > 99) "99+" else count.toString(),
    )
}
