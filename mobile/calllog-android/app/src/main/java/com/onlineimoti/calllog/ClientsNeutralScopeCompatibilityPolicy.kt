package com.onlineimoti.calllog

/** Keeps the no-filter Clients fallback limited to an actually empty canonical page. */
internal object ClientsNeutralScopeCompatibilityPolicy {
    fun shouldRetryWithoutCompany(canonicalRowCount: Int): Boolean = canonicalRowCount == 0

    fun shouldAcceptLegacyPage(rowCount: Int): Boolean = rowCount > 0
}
