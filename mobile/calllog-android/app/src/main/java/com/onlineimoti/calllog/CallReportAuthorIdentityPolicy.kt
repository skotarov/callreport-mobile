package com.onlineimoti.calllog

/** Resolves note ownership without treating a mutable display name as identity. */
internal object CallReportAuthorIdentityPolicy {
    fun isOtherAuthor(event: CallReportHistoryEvent?, principal: CallReportHistoryPrincipal): Boolean {
        event ?: return false
        event.isMine?.let { return !it }

        val authorProfileId = event.authorProfileId.trim()
        val currentProfileId = principal.profileId.trim()
        if (authorProfileId.isNotBlank() && currentProfileId.isNotBlank()) {
            return authorProfileId != currentProfileId
        }

        val authorBrokerId = event.authorBrokerId.trim()
        val currentBrokerId = principal.brokerId.trim()
        if (authorBrokerId.isNotBlank() && currentBrokerId.isNotBlank()) {
            return authorBrokerId != currentBrokerId
        }

        if (event.canEdit == true) return false
        val authorName = event.authorBrokerName.trim()
        val currentName = principal.brokerName.trim()
        if (authorName.isNotBlank() && currentName.isNotBlank()) {
            return !authorName.equals(currentName, ignoreCase = true)
        }
        return false
    }

    fun canEdit(event: CallReportHistoryEvent?, principal: CallReportHistoryPrincipal): Boolean {
        event ?: return true
        event.canEdit?.let { return it }
        event.isMine?.let { return it }
        return !isOtherAuthor(event, principal)
    }
}
