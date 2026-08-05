package com.onlineimoti.calllog

import android.content.Context

internal object HomeCrmContactCandidatesServer {
    fun load(
        context: Context,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
    ): List<PhoneCallRecord> {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        // This method is called from the existing Clients/search worker threads.
        // Import pre-profile CRM switches once, then refresh the profile cache
        // before applying crm_only.
        val legacyUploaded = LegacyCrmContactMigration.migrateIfNeeded(appContext, config)
        CrmContactSyncStore.refreshFromServer(appContext, force = legacyUploaded)
        return ServerCrmContactsClient.lookup(
            config = config,
            filterState = filterState,
            searchQuery = searchQuery,
            context = appContext,
        )
    }
}
