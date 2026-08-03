package com.onlineimoti.calllog

import android.content.Context
import kotlin.math.abs

internal enum class CallReportHistoryRowKind { PHONE, SMS, NOTE }

internal data class CallReportHistoryRow(
    val kind: CallReportHistoryRowKind,
    val timeMs: Long,
    val phone: String,
    val direction: String = "",
    val status: String = "",
    val durationSeconds: Long = 0L,
    val text: String = "",
    val localCall: PhoneCallRecord? = null,
    val localSms: SmsMessageRecord? = null,
    val localNote: ContactCallNote? = null,
    val serverEvent: CallReportHistoryEvent? = null,
    /** Selected company/case of this concrete conversation. */
    val companyId: String = "",
    /** Display name resolved from the authenticated server principal. */
    val companyName: String = "",
    /** Persisted acknowledgement from a prior successful sync; independent of current lookup. */
    val locallyConfirmedOnServer: Boolean = false,
    val serverNewer: Boolean = false,
    val editable: Boolean = false,
    /** True only when the confirmed server author is different from the current authenticated broker. */
    val authorIsOtherBroker: Boolean = false,
) {
    val authorName: String
        get() = serverEvent?.authorBrokerName.orEmpty()

    val isServerOnly: Boolean
        get() = localCall == null && localSms == null && localNote == null && serverEvent != null

    /** A cloud is shown for either a freshly returned server record or a persisted local acknowledgement. */
    val hasServerCopy: Boolean
        get() = serverEvent != null || locallyConfirmedOnServer
}

/** Merges Android history and canonical server history without duplicating matching records. */
internal object CallReportHistoryMerge {
    private const val FALLBACK_MATCH_WINDOW_MS = 10 * 60 * 1000L
    private const val NOTE_FALLBACK_MATCH_WINDOW_MS = 2 * 60 * 1000L

    fun merge(
        context: Context,
        phone: String,
        principal: CallReportHistoryPrincipal,
        localCalls: List<PhoneCallRecord>,
        localSms: List<SmsMessageRecord>,
        localNotes: List<ContactCallNote>,
        serverEvents: List<CallReportHistoryEvent>,
    ): List<CallReportHistoryRow> {
        ServerRecordIndex.markConfirmed(context, serverEvents.map { it.clientEventId })
        val serverByClientId = serverEvents
            .filter { it.clientEventId.isNotBlank() }
            .associateBy { it.clientEventId }
        val companyNamesById = principal.companies.associate { company ->
            company.id.trim() to company.name.trim().ifBlank { company.id.trim() }
        }
        fun companyNameFor(companyId: String): String = companyNamesById[companyId.trim()].orEmpty()
        val usedServerIds = linkedSetOf<String>()
        val usedServerIndexes = linkedSetOf<Int>()
        val rows = mutableListOf<CallReportHistoryRow>()

        localCalls.forEach { call ->
            val expectedId = call.providerId.takeIf { it.isNotBlank() }
                ?.let { ServerRecordIndex.communicationEventId(context, "phone", it) }
                .orEmpty()
            val localConfirmed = expectedId.isNotBlank() && ServerRecordIndex.isConfirmed(context, expectedId)
            val match = serverByClientId[expectedId]
                ?: fallbackMatch(serverEvents, usedServerIndexes, phone, "phone", call.direction, call.startedAt)
            if (match != null) markUsed(match, serverEvents, usedServerIds, usedServerIndexes)
            val companyId = match?.companyId.orEmpty()
            rows += CallReportHistoryRow(
                kind = CallReportHistoryRowKind.PHONE,
                timeMs = call.startedAt,
                phone = call.number,
                direction = call.direction,
                status = match?.status.orEmpty(),
                durationSeconds = call.durationSeconds.takeIf { it > 0L } ?: match?.durationSeconds.orEmpty(),
                localCall = call,
                serverEvent = match,
                companyId = companyId,
                companyName = companyNameFor(companyId),
                locallyConfirmedOnServer = localConfirmed,
                authorIsOtherBroker = CallReportAuthorIdentityPolicy.isOtherAuthor(match, principal),
            )
        }

        localSms.forEach { sms ->
            val expectedId = sms.providerId.takeIf { it.isNotBlank() }
                ?.let { ServerRecordIndex.communicationEventId(context, "sms", it) }
                .orEmpty()
            val localConfirmed = expectedId.isNotBlank() && ServerRecordIndex.isConfirmed(context, expectedId)
            val direction = if (sms.isOutgoing) "out" else "in"
            val match = serverByClientId[expectedId]
                ?: fallbackMatch(serverEvents, usedServerIndexes, phone, "sms", direction, sms.timestampMs)
            if (match != null) markUsed(match, serverEvents, usedServerIds, usedServerIndexes)
            val companyId = match?.companyId.orEmpty().ifBlank {
                SmsCompanyAssignmentStore.companyIdFor(context, phone, sms.providerId)
            }
            rows += CallReportHistoryRow(
                kind = CallReportHistoryRowKind.SMS,
                timeMs = sms.timestampMs,
                phone = phone,
                direction = direction,
                status = match?.status.orEmpty(),
                text = sms.body,
                localSms = sms,
                serverEvent = match,
                companyId = companyId,
                companyName = companyNameFor(companyId),
                locallyConfirmedOnServer = localConfirmed,
                authorIsOtherBroker = CallReportAuthorIdentityPolicy.isOtherAuthor(match, principal),
            )
        }

        localNotes.forEach { note ->
            val clientNoteId = note.clientNoteId.ifBlank {
                LocalNotesFileStore.clientNoteIdForCall(phone, note.callAt, note.direction)
            }
            val expectedId = ServerRecordIndex.callNoteEventId(context, clientNoteId)
            val localConfirmed = expectedId.isNotBlank() && ServerRecordIndex.isConfirmed(context, expectedId)
            val directServerId = note.serverClientEventId.trim()
            val match = directServerId.takeIf { it.isNotBlank() }?.let(serverByClientId::get)
                ?: serverByClientId[expectedId]
                ?: legacyTopicCallMatch(serverEvents, usedServerIndexes, phone, clientNoteId, note.direction, note.callAt)
                ?: fallbackNoteMatch(serverEvents, usedServerIndexes, phone, note)
            if (match != null) markUsed(match, serverEvents, usedServerIds, usedServerIndexes)
            val foreignAuthor = CallReportAuthorIdentityPolicy.isOtherAuthor(match, principal)
            val serverNewer = match != null && note.savedAt > 0L && match.updatedAtMs > note.savedAt && match.note != note.note
            val companyId = match?.companyId.orEmpty().ifBlank { note.companyId }
            rows += CallReportHistoryRow(
                kind = CallReportHistoryRowKind.NOTE,
                timeMs = maxOf(note.callAt.takeIf { it > 0L } ?: note.savedAt, match?.occurredAtMs ?: 0L),
                phone = phone,
                direction = note.direction.ifBlank { match?.direction.orEmpty() },
                durationSeconds = note.durationSeconds.takeIf { it > 0L } ?: match?.durationSeconds.orEmpty(),
                text = if (serverNewer) match?.note.orEmpty() else note.note,
                localNote = note,
                serverEvent = match,
                companyId = companyId,
                companyName = companyNameFor(companyId),
                locallyConfirmedOnServer = localConfirmed,
                serverNewer = serverNewer,
                editable = CallReportAuthorIdentityPolicy.canEdit(match, principal),
                authorIsOtherBroker = foreignAuthor,
            )
        }

        serverEvents.forEachIndexed { index, event ->
            if (event.clientEventId.isNotBlank() && event.clientEventId in usedServerIds) return@forEachIndexed
            if (index in usedServerIndexes) return@forEachIndexed
            val kind = when (event.communicationType.lowercase()) {
                "sms" -> CallReportHistoryRowKind.SMS
                "note" -> CallReportHistoryRowKind.NOTE
                else -> CallReportHistoryRowKind.PHONE
            }
            val foreignAuthor = CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal)
            rows += CallReportHistoryRow(
                kind = kind,
                timeMs = event.occurredAtMs.takeIf { it > 0L } ?: event.updatedAtMs,
                phone = event.phone,
                direction = event.direction,
                status = event.status,
                durationSeconds = event.durationSeconds,
                text = event.note,
                serverEvent = event,
                companyId = event.companyId,
                companyName = companyNameFor(event.companyId),
                // Every non-foreign timeline note returned by history_lookup has a
                // timestamp. Its original client_event_id is preserved by the UI
                // and server editor, so no duplicate note is inserted.
                editable = kind == CallReportHistoryRowKind.NOTE && CallReportAuthorIdentityPolicy.canEdit(event, principal),
                authorIsOtherBroker = foreignAuthor,
            )
        }

        return rows.sortedByDescending { it.timeMs }
    }

    private fun legacyTopicCallMatch(
        events: List<CallReportHistoryEvent>,
        usedIndexes: Set<Int>,
        phone: String,
        stableCallId: String,
        direction: String,
        callAt: Long,
    ): CallReportHistoryEvent? {
        if (stableCallId.isBlank()) return null
        val marker = ":topic:call:$stableCallId:"
        events.forEachIndexed { index, event ->
            if (index in usedIndexes) return@forEachIndexed
            if (!event.communicationType.equals("note", ignoreCase = true)) return@forEachIndexed
            if (HomeCallPageLoader.noteKey(event.phone) != HomeCallPageLoader.noteKey(phone)) return@forEachIndexed
            if (!event.clientEventId.contains(marker)) return@forEachIndexed
            if (direction.isNotBlank() && event.direction.isNotBlank() && event.direction != direction) return@forEachIndexed
            if (callAt > 0L && event.occurredAtMs != callAt) return@forEachIndexed
            return event
        }
        return null
    }

    /**
     * Older local rows may have been written before their server client_event_id was
     * persisted. Match the same note by phone, text and nearby call timestamp so the
     * local and cloud copies render as one conversation note rather than two rows.
     */
    private fun fallbackNoteMatch(
        events: List<CallReportHistoryEvent>,
        usedIndexes: Set<Int>,
        phone: String,
        note: ContactCallNote,
    ): CallReportHistoryEvent? {
        val expectedPhone = HomeCallPageLoader.noteKey(phone)
        val expectedText = normalizeNote(note.note)
        val expectedTime = note.callAt.takeIf { it > 0L } ?: note.savedAt
        if (expectedPhone.isBlank() || expectedText.isBlank() || expectedTime <= 0L) return null

        var best: CallReportHistoryEvent? = null
        var bestDelta = Long.MAX_VALUE
        events.forEachIndexed { index, event ->
            if (index in usedIndexes) return@forEachIndexed
            if (!event.communicationType.equals("note", ignoreCase = true)) return@forEachIndexed
            if (HomeCallPageLoader.noteKey(event.phone) != expectedPhone) return@forEachIndexed
            if (normalizeNote(event.note) != expectedText) return@forEachIndexed
            if (
                note.direction.isNotBlank() && event.direction.isNotBlank() &&
                note.direction != event.direction
            ) return@forEachIndexed
            val eventTime = event.occurredAtMs.takeIf { it > 0L } ?: event.updatedAtMs
            val delta = abs(eventTime - expectedTime)
            if (delta <= NOTE_FALLBACK_MATCH_WINDOW_MS && delta < bestDelta) {
                best = event
                bestDelta = delta
            }
        }
        return best
    }

    private fun fallbackMatch(
        events: List<CallReportHistoryEvent>,
        usedIndexes: Set<Int>,
        phone: String,
        type: String,
        direction: String,
        timestampMs: Long,
    ): CallReportHistoryEvent? {
        val expectedPhone = HomeCallPageLoader.noteKey(phone)
        var best: CallReportHistoryEvent? = null
        var bestDelta = Long.MAX_VALUE
        events.forEachIndexed { index, event ->
            if (index in usedIndexes || event.communicationType.lowercase() != type) return@forEachIndexed
            if (HomeCallPageLoader.noteKey(event.phone) != expectedPhone) return@forEachIndexed
            if (direction.isNotBlank() && event.direction.isNotBlank() && event.direction != direction) return@forEachIndexed
            val delta = abs(event.occurredAtMs - timestampMs)
            if (delta <= FALLBACK_MATCH_WINDOW_MS && delta < bestDelta) {
                best = event
                bestDelta = delta
            }
        }
        return best
    }

    private fun markUsed(
        event: CallReportHistoryEvent,
        events: List<CallReportHistoryEvent>,
        usedIds: MutableSet<String>,
        usedIndexes: MutableSet<Int>,
    ) {
        if (event.clientEventId.isNotBlank()) usedIds += event.clientEventId
        val index = events.indexOfFirst { candidate ->
            candidate.serverId == event.serverId && candidate.clientEventId == event.clientEventId
        }
        if (index >= 0) usedIndexes += index
    }

    private fun normalizeNote(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun Long?.orEmpty(): Long = this ?: 0L
}
