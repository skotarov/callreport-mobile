package com.onlineimoti.calllog

import android.content.Context

internal object HomeCrmContactCandidatesServer {
    fun load(
        context: Context,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
    ): List<PhoneCallRecord> {
        val appContext = context.applicationContext
        // This method is called from the existing Clients/search worker threads.
        // Keep the local profile cache current before applying crm_only.
        CrmContactSyncStore.refreshFromServer(appContext)
        return ServerCrmContactsClient.lookup(
            config = ConfigStore.load(appContext),
            filterState = filterState,
            searchQuery = searchQuery,
            context = appContext,
        )
    }
}
