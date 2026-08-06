package com.onlineimoti.calllog

internal class CallReportHistoryState {
    var activePhone = ""
    var started = false
    var localLoading = false
    var serverLoading = false
    var prepareLoading = false
    var serverLoaded = false
    var localDataDirty = false
    var serverDataDirty = false
    var remoteSignature = ""
    var localGeneration = 0
    var serverGeneration = 0
    var prepareGeneration = 0
    var lastRenderedState: CallReportHistoryRenderedState? = null
    var forceRenderAfterPrepare = false

    var localCalls: List<PhoneCallRecord> = emptyList()
    var latestLocalCall: PhoneCallRecord? = null
    var localSms: List<SmsMessageRecord> = emptyList()
    var localNotes: List<ContactCallNote> = emptyList()
    var localGeneralNote = ""
    var localGeneralNotePending = false
    var contactExists = false
    var companyScopeAvailable = false
    var serverHistory = CallReportHistoryLookupResult()
    var prepared = HistoryPreparedSnapshot()
    var loadError = ""

    fun isLoading(): Boolean = localLoading || serverLoading || prepareLoading

    fun currentLocalSnapshot(): HistoryLocalSnapshot = HistoryLocalSnapshot(
        calls = localCalls,
        latestCall = latestLocalCall,
        sms = localSms,
        callNotes = localNotes,
        generalNote = localGeneralNote,
        generalNotePending = localGeneralNotePending,
        contactExists = contactExists,
        companyScopeAvailable = companyScopeAvailable,
    )

    fun applyLocalSnapshot(snapshot: HistoryLocalSnapshot) {
        localCalls = snapshot.calls
        latestLocalCall = snapshot.latestCall ?: snapshot.calls.firstOrNull()
        localSms = snapshot.sms
        localNotes = snapshot.callNotes
        localGeneralNote = snapshot.generalNote
        localGeneralNotePending = snapshot.generalNotePending
        contactExists = snapshot.contactExists
        companyScopeAvailable = snapshot.companyScopeAvailable
    }

    fun hasServerRecordsFor(phone: String): Boolean {
        if (!serverLoaded || phone.isBlank() || phone != activePhone) return false
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        if (phoneKey.isBlank()) return false
        return serverHistory.events.any { event ->
            HomeCallPageLoader.noteKey(event.phone) == phoneKey &&
                event.communicationType.equals("note", ignoreCase = true) &&
                event.note.trim().isNotBlank()
        }
    }

    fun currentRenderedState(): CallReportHistoryRenderedState = CallReportHistoryRenderedState(
        showLoadingPlaceholder = isLoading() && !hasVisibleHistoryContent(),
        serverLoaded = serverLoaded,
        localCalls = localCalls,
        latestLocalCall = latestLocalCall,
        localSms = localSms,
        localNotes = localNotes,
        localGeneralNote = localGeneralNote,
        localGeneralNotePending = localGeneralNotePending,
        contactExists = contactExists,
        companyScopeAvailable = companyScopeAvailable,
        serverHistory = serverHistory,
        prepared = prepared,
        loadError = loadError,
    )

    private fun hasVisibleHistoryContent(): Boolean =
        localGeneralNote.isNotBlank() ||
            prepared.rows.isNotEmpty() ||
            prepared.fullLogEntries.isNotEmpty() ||
            prepared.companyMainNotes.any { note -> note.note.isNotBlank() } ||
            prepared.unscopedServerMainNote != null
}

internal data class CallReportHistoryRenderedState(
    val showLoadingPlaceholder: Boolean,
    val serverLoaded: Boolean,
    val localCalls: List<PhoneCallRecord>,
    val latestLocalCall: PhoneCallRecord?,
    val localSms: List<SmsMessageRecord>,
    val localNotes: List<ContactCallNote>,
    val localGeneralNote: String,
    val localGeneralNotePending: Boolean,
    val contactExists: Boolean,
    val companyScopeAvailable: Boolean,
    val serverHistory: CallReportHistoryLookupResult,
    val prepared: HistoryPreparedSnapshot,
    val loadError: String,
)
