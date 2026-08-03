package com.onlineimoti.calllog

import android.content.Context

internal data class HomeCrmClientServerNotesSnapshot(
    val contactNotesByNumber: Map<String, String> = emptyMap(),
    val callNotesByCall: Map<String, HomeCallNote> = emptyMap(),
)

/** Server-only note helpers for the Clients page. */
internal object HomeCrmClientServerNotes {
    fun snapshot(
        context: Context,
        contacts: List<PhoneCallRecord>,
    ): HomeCrmClientServerNotesSnapshot {
        if (contacts.isEmpty()) return HomeCrmClientServerNotesSnapshot()
        val config = ConfigStore.load(context.applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) return HomeCrmClientServerNotesSnapshot()
        val phones = contacts
            .map { it.number }
            .filter { HomeCallPageLoader.noteKey(it).isNotBlank() }
            .distinctBy(HomeCallPageLoader::noteKey)
        if (phones.isEmpty()) return HomeCrmClientServerNotesSnapshot()
        val history = runCatching {
            CallReportHistoryLookupClient.lookupMany(config, phones, context.applicationContext)
        }.getOrDefault(CallReportHistoryLookupResult())
        return HomeCrmClientServerNotesSnapshot(
            contactNotesByNumber = unscopedGeneralNotes(contacts, history),
            callNotesByCall = latestCallNotes(contacts, history),
        )
    }

    fun latestCallNotes(
        context: Context,
        contacts: List<PhoneCallRecord>,
    ): Map<String, HomeCallNote> = snapshot(context, contacts).callNotesByCall

    fun unscopedGeneralNotes(
        context: Context,
        contacts: List<PhoneCallRecord>,
    ): Map<String, String> = snapshot(context, contacts).contactNotesByNumber

    fun latestCallNotes(
        contacts: List<PhoneCallRecord>,
        history: CallReportHistoryLookupResult,
    ): Map<String, HomeCallNote> {
        if (contacts.isEmpty() || history.events.isEmpty()) return emptyMap()
        val rowByPhoneKey = contacts
            .filter { HomeCallPageLoader.noteKey(it.number).isNotBlank() }
            .associateBy { HomeCallPageLoader.noteKey(it.number) }
        if (rowByPhoneKey.isEmpty()) return emptyMap()

        val byRowAndScope = linkedMapOf<String, LinkedHashMap<String, HomeCallNote>>()
        history.events.forEach { event ->
            val phoneKey = HomeCallPageLoader.noteKey(event.phone)
            val row = rowByPhoneKey[phoneKey] ?: return@forEach
            if (!isBlueServerNote(event)) return@forEach
            val changedAt = maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs)
            val key = HomeCallNotesResolver.keyFor(row)
            val scope = event.companyId.trim().ifBlank { "server" }
            val note = HomeCallNote(
                text = event.note.trim(),
                updatedAtMs = changedAt,
                fromServer = true,
                authorName = event.authorBrokerName.trim(),
                companyId = event.companyId.trim(),
                serverClientEventId = event.clientEventId.trim(),
                editable = !isOtherBrokerAuthor(event, history.principal),
            )
            val bucket = byRowAndScope.getOrPut(key) { linkedMapOf() }
            val current = bucket[scope]
            if (current == null || changedAt >= current.updatedAtMs) bucket[scope] = note
        }
        return byRowAndScope.mapNotNull { (key, bucket) ->
            val notes = bucket.values.sortedWith(
                compareByDescending<HomeCallNote> { it.editable }
                    .thenBy { it.companyId.lowercase() }
                    .thenByDescending { it.updatedAtMs },
            )
            notes.firstOrNull()?.let { primary -> key to primary.copy(relatedNotes = notes.drop(1)) }
        }.toMap()
    }

    private fun unscopedGeneralNotes(
        contacts: List<PhoneCallRecord>,
        history: CallReportHistoryLookupResult,
    ): Map<String, String> {
        if (contacts.isEmpty() || history.events.isEmpty()) return emptyMap()
        val requestedKeys = contacts
            .mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.number) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        if (requestedKeys.isEmpty()) return emptyMap()

        val latest = linkedMapOf<String, Pair<Long, String>>()
        history.events.forEach { event ->
            val phoneKey = HomeCallPageLoader.noteKey(event.phone)
            if (phoneKey.isBlank() || phoneKey !in requestedKeys) return@forEach
            if (event.companyId.isNotBlank()) return@forEach
            if (!CallReportServerNoteClassifier.isExplicitGeneralNote(event)) return@forEach
            val note = event.note.trim()
            if (note.isBlank()) return@forEach
            val changedAt = maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs)
            val current = latest[phoneKey]
            if (current == null || changedAt >= current.first) {
                latest[phoneKey] = changedAt to ServerNoteVisuals.prefixed(note)
            }
        }
        return latest.mapValues { it.value.second }
    }

    private fun isBlueServerNote(event: CallReportHistoryEvent): Boolean {
        return event.communicationType.equals("note", ignoreCase = true) &&
            event.note.trim().isNotBlank() &&
            !CallReportServerNoteClassifier.isExplicitGeneralNote(event)
    }


}
