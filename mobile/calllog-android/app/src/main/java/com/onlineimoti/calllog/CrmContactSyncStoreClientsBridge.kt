package com.onlineimoti.calllog

import android.content.Context

/** Read-only bridge for the Clients cache; the existing CRM LWW store remains authoritative. */
internal fun CrmContactSyncStore.records(context: Context): List<CrmSyncRecord> = activeRecords(context)
