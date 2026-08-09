package com.onlineimoti.calllog

import android.content.Context

internal object HomeCrmContactCandidatesServer {
    fun load(
        context: Context,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
    ): List<PhoneCallRecord> = loadPage(
        context = context,
        filterState = filterState,
        searchQuery = searchQuery,
        limit = ServerCrmContactsClient.DEFAULT_PAGE_SIZE,
        offset = 0,
    ).calls

    fun loadPage(
        context: Context,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
        limit: Int,
        offset: Int,
    ): ServerCrmContactsPage {
        val appContext = context.applicationContext
        // Older builds keyed private CRM markers by the profile email/phone.
        // Recover those proven aliases before synchronizing the current userId scope.
        CrmContactProfileScopeMigration.migrateKnownAliases(appContext)
        // Keep the existing LWW CRM store canonical. Its pending local edits survive
        // offline operation and are reconciled before the Clients request is made.
        CrmContactSyncStore.refreshFromServer(appContext)
        return ServerCrmContactsClient.lookupPage(
            config = ConfigStore.load(appContext),
            filterState = filterState,
            searchQuery = searchQuery,
            limit = limit,
            offset = offset,
            context = appContext,
        )
    }
}
