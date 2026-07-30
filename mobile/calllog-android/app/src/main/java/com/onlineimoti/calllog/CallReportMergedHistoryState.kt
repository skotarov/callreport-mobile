package com.onlineimoti.calllog

/** Mutable History snapshot kept separate from loading and rendering coordination. */
internal class CallReportMergedHistoryState {
    var activePhone = ""
    var started = false
    var serverLoaded = false
    var localDataDirty = false
    var serverDataDirty = false
    var remoteSignature = ""
    var lastRenderedState: HistoryRenderedState? = null
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

    fun reset(phone: String, signature: String) {
        activePhone = phone
        started = false
        serverLoaded = false
        localDataDirty = false
        serverDataDirty = false
        remoteSignature = signature
        applyLocalSnapshot(HistoryLocalSnapshot())
        serverHistory = CallReportHistoryLookupResult()
        prepared = HistoryPreparedSnapshot()
        loadError = ""
        lastRenderedState = null
        forceRenderAfterPrepare = false
    }

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

    fun renderedState(isLoading: Boolean): HistoryRenderedState = HistoryRenderedState(
        showLoadingPlaceholder = isLoading && !hasVisibleHistoryContent(),
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
        localGeneralNote.isNotBlank() || prepared.rows.isNotEmpty() || prepared.fullLogEntries.isNotEmpty() ||
            prepared.companyMainNotes.any { note -> note.note.isNotBlank() } || prepared.unscopedServerMainNote != null
}

internal data class HistoryRenderedState(
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
