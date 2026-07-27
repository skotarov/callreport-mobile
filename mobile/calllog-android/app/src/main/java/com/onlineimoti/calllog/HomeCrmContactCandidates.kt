package com.onlineimoti.calllog

import android.content.Context
import android.provider.CallLog

/**
 * Contacts mode ultimately comes from the authenticated Relationship Manager
 * profile. While the server is synchronizing, the CRM-only filter can render
 * the profile's local marker cache immediately.
 */
internal object HomeCrmContactCandidates {
    private const val UNKNOWN_CRM_LOOKBACK_MS = 14L * 24L * 60L * 60L * 1_000L

    fun load(
        context: Context,
        filterState: HomeCrmFilterState = HomeCrmFilterState(),
        searchQuery: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): List<PhoneCallRecord> {
        val appContext = context.applicationContext
        return HomeCrmContactCandidatesServer.load(appContext, filterState, searchQuery)
    }

    /** Fast local snapshot used only as a provisional CRM-only Clients render. */
    fun loadLocal(context: Context, nowMs: Long = System.currentTimeMillis()): List<PhoneCallRecord> {
        val appContext = context.applicationContext
        val markers = CrmContactSyncStore.activeRecords(appContext)
        if (markers.isEmpty()) return emptyList()

        val knownByKey = ContactSearchProvider.crmEnabledContacts(appContext)
            .associateBy { PhoneNormalizer.key(it.phone) }
        val recentUnknownByKey = recentUnknownCrmCalls(appContext, nowMs)
            .associateBy { HomeCallPageLoader.noteKey(it.number) }

        return markers.mapNotNull { marker ->
            val key = PhoneNormalizer.key(marker.phone)
            if (key.isBlank()) return@mapNotNull null
            val known = knownByKey[key]
            val recent = recentUnknownByKey[key]
            when {
                known != null -> PhoneCallRecord(
                    number = known.phone,
                    name = known.name,
                    direction = "",
                    startedAt = marker.updatedAtMs,
                    durationSeconds = 0L,
                )
                recent != null -> recent.copy(startedAt = maxOf(recent.startedAt, marker.updatedAtMs))
                else -> PhoneCallRecord(
                    number = marker.phone.ifBlank { key },
                    name = "",
                    direction = "",
                    startedAt = marker.updatedAtMs,
                    durationSeconds = 0L,
                )
            }
        }.distinctBy { HomeCallPageLoader.noteKey(it.number) }
    }

    private fun recentUnknownCrmCalls(context: Context, nowMs: Long): List<PhoneCallRecord> {
        if (!PhoneCallReader.hasCallLogPermission(context)) return emptyList()
        val enabledKeys = CrmContactSyncStore.enabledPhoneKeys(context)
        if (enabledKeys.isEmpty()) return emptyList()
        val cutoffMs = nowMs - UNKNOWN_CRM_LOOKBACK_MS
        val newestByPhoneKey = linkedMapOf<String, PhoneCallRecord>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
        )
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.DATE}>=?",
                arrayOf(cutoffMs.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                while (cursor.moveToNext()) {
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                    val phoneKey = HomeCallPageLoader.noteKey(number)
                    if (phoneKey.isBlank() || phoneKey !in enabledKeys || phoneKey in newestByPhoneKey) continue
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                    val startedAt = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                    newestByPhoneKey[phoneKey] = PhoneCallRecord(
                        number = number,
                        name = name,
                        direction = "",
                        startedAt = startedAt,
                        durationSeconds = 0L,
                    )
                }
            }
        }
        if (newestByPhoneKey.isEmpty()) return emptyList()

        val kinds = HomeCallPageLoader.crmContactKinds(
            context,
            newestByPhoneKey.values.map { it.number },
        )
        return newestByPhoneKey.values
            .filter { call ->
                kinds[HomeCallPageLoader.noteKey(call.number)] == HomeCrmContactKind.UNKNOWN
            }
            .sortedByDescending { it.startedAt }
    }
}
