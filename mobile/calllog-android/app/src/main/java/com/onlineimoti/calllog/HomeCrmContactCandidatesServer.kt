package com.onlineimoti.calllog

import android.content.Context

internal object HomeCrmContactCandidatesServer {
    fun load(
        context: Context,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
    ): List<PhoneCallRecord> {
        val appContext = context.applicationContext
        // Older builds keyed private CRM markers by the profile email/phone.
        // Recover those proven aliases before synchronizing the current userId scope.
        CrmContactProfileScopeMigration.migrateKnownAliases(appContext)
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
