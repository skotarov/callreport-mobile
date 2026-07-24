package com.onlineimoti.calllog

import android.content.Context
import kotlin.math.abs

/** Local sources needed by the embedded History full-log tab. */
internal data class FilteredFullLogLocalData(
    val calls: List<PhoneCallRecord> = emptyList(),
    val sms: List<SmsMessageRecord> = emptyList(),
    val notes: List<ContactCallNote> = emptyList(),
)

/** Single source of truth for loading, merging and grouping one contact's complete timeline. */
internal object FilteredFullLogLoader {
    fun loadLocal(context: Context, phone: String): FilteredFullLogLocalData {
        if (phone.isBlank()) return FilteredFullLogLocalData()
        return FilteredFullLogLocalData(
            calls = PhoneCallReader.callsForPhone(context, phone, limit = SOURCE_CALL_LIMIT),
            sms = SmsMessageReader.messagesForPhone(context, phone, limit = SOURCE_SMS_LIMIT),
            notes = ContactNoteReader.callNotesForPhone(context, phone),
        )
    }

    fun prepare(
        context: Context,
        phone: String,
        remoteEnabled: Boolean,
        principal: CallReportHistoryPrincipal,
        local: FilteredFullLogLocalData,
        serverEvents: List<CallReportHistoryEvent>,
    ): List<FilteredFullLogEntry> {
        if (phone.isBlank()) return emptyList()
        val callTimelineServerEvents = if (remoteEnabled) {
            // General/contact notes are presented in the yellow main-note area and
            // must not become standalone blue cards in History > Calls. Concrete
            // call notes remain and are attached to their matching phone call below.
            serverEvents.filterNot(CallReportServerNoteClassifier::isGeneralNote)
        } else {
            emptyList()
        }
        val merged = CallReportHistoryMerge.merge(
            context = context,
            phone = phone,
            principal = if (remoteEnabled) principal else CallReportHistoryPrincipal(),
            localCalls = local.calls,
            localSms = local.sms,
            localNotes = local.notes,
            serverEvents = callTimelineServerEvents,
        )
        return groupedEntries(merged)
    }

    /**
     * Builds an immediately displayable local-only timeline from a cached provider snapshot.
     * It deliberately avoids disk/provider lookups and server-confirmation reads on the main thread.
     */
    fun prepareCachedLocal(phone: String, local: FilteredFullLogLocalData): List<FilteredFullLogEntry> =
        groupedEntries(cachedLocalRows(phone, local))

    internal fun cachedLocalRows(
        phone: String,
        local: FilteredFullLogLocalData,
    ): List<CallReportHistoryRow> {
        if (phone.isBlank()) return emptyList()
        val rows = ArrayList<CallReportHistoryRow>(local.calls.size + local.sms.size + local.notes.size)
        local.calls.forEach { call ->
            rows += CallReportHistoryRow(
                kind = CallReportHistoryRowKind.PHONE,
                timeMs = call.startedAt,
                phone = call.number.ifBlank { phone },
                direction = call.direction,
                durationSeconds = call.durationSeconds,
                localCall = call,
            )
        }
        local.sms.forEach { sms ->
            rows += CallReportHistoryRow(
                kind = CallReportHistoryRowKind.SMS,
                timeMs = sms.timestampMs,
                phone = phone,
                direction = if (sms.isOutgoing) "out" else "in",
                text = sms.body,
                localSms = sms,
            )
        }
        local.notes.forEach { note ->
            rows += CallReportHistoryRow(
                kind = CallReportHistoryRowKind.NOTE,
                timeMs = note.callAt.takeIf { it > 0L } ?: note.savedAt,
                phone = phone,
                direction = note.direction,
                durationSeconds = note.durationSeconds,
                text = note.note,
                localNote = note,
                companyId = note.companyId,
                editable = true,
            )
        }
        return rows.sortedByDescending { row -> row.timeMs }
    }

    internal fun groupedEntries(timeline: List<CallReportHistoryRow>): List<FilteredFullLogEntry> {
        val callIndexes = timeline.mapIndexedNotNull { index, row ->
            index.takeIf { row.kind == CallReportHistoryRowKind.PHONE }
        }
        val notesByCall = mutableMapOf<Int, MutableList<CallReportHistoryRow>>()
        val attachedIndexes = mutableSetOf<Int>()
        timeline.forEachIndexed { noteIndex, note ->
            if (note.kind != CallReportHistoryRowKind.NOTE) return@forEachIndexed
            if (note.serverEvent?.let(CallReportServerNoteClassifier::isGeneralNote) == true) return@forEachIndexed
            val callIndex = matchingCallIndex(note, callIndexes, timeline) ?: return@forEachIndexed
            notesByCall.getOrPut(callIndex) { mutableListOf() }.add(note)
            attachedIndexes += noteIndex
        }
        return timeline.mapIndexedNotNull { index, row ->
            if (row.kind == CallReportHistoryRowKind.NOTE && index in attachedIndexes) null
            else FilteredFullLogEntry(row, notesByCall[index].orEmpty().sortedBy { it.timeMs })
        }
    }

    private fun matchingCallIndex(
        note: CallReportHistoryRow,
        callIndexes: List<Int>,
        timeline: List<CallReportHistoryRow>,
    ): Int? {
        val notePhone = HomeCallPageLoader.noteKey(note.phone)
        var closestIndex: Int? = null
        var closestDelta = Long.MAX_VALUE
        callIndexes.forEach { callIndex ->
            val call = timeline[callIndex]
            if (HomeCallPageLoader.noteKey(call.phone) != notePhone) return@forEach
            if (note.direction.isNotBlank() && call.direction.isNotBlank() && note.direction != call.direction) return@forEach
            val delta = abs(note.timeMs - call.timeMs)
            if (delta <= NOTE_CALL_MATCH_WINDOW_MS && delta < closestDelta) {
                closestIndex = callIndex
                closestDelta = delta
            }
        }
        return closestIndex
    }

    private const val SOURCE_CALL_LIMIT = 200
    private const val SOURCE_SMS_LIMIT = 150
    private const val NOTE_CALL_MATCH_WINDOW_MS = 90_000L
}
