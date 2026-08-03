package com.onlineimoti.calllog

import kotlin.math.abs

/** A blue Home-row note, selected from either local storage or server history. */
internal data class HomeCallNote(
    val text: String,
    val updatedAtMs: Long,
    val fromServer: Boolean,
    val authorName: String = "",
    /** Firm assigned to the same concrete conversation, when known. */
    val companyId: String = "",
    /** Lets Home edit an existing server-only note in place instead of duplicating it. */
    val serverClientEventId: String = "",
    /** Foreign server notes stay visible but must not be opened for mutation. */
    val editable: Boolean = true,
    /** Other independent Local/company notes attached to the same call. */
    val relatedNotes: List<HomeCallNote> = emptyList(),
) {
    fun expandedNotes(): List<HomeCallNote> = buildList {
        add(copy(relatedNotes = emptyList()))
        relatedNotes.forEach { related ->
            add(related.copy(relatedNotes = emptyList()))
        }
    }
}

/**
 * Resolves notes for the exact Call Log rows visible on Home. General/contact
 * notes are deliberately excluded: blue cards belong only to that conversation.
 */
internal object HomeCallNotesResolver {
    fun localNotes(context: android.content.Context, calls: List<PhoneCallRecord>): Map<String, HomeCallNote> {
        if (calls.isEmpty()) return emptyMap()
        val notesByPhoneKey = calls
            .filterNot { it.isSms }
            .map { it.number }
            .distinctBy(HomeCallPageLoader::noteKey)
            .associateBy(
                keySelector = HomeCallPageLoader::noteKey,
                valueTransform = { phone -> ContactNoteReader.callNotesForPhone(context, phone) },
            )
        val result = linkedMapOf<String, HomeCallNote>()
        val claimedNotes = hashSetOf<String>()
        calls.filterNot { it.isSms }.forEach { call ->
            val local = notesByPhoneKey[HomeCallPageLoader.noteKey(call.number)]
                .orEmpty()
                .filter { note -> sameLocalCall(call, note) }
                .filterNot { note -> claimedNoteKey(note) in claimedNotes }
                .maxWithOrNull(compareBy<ContactCallNote> { localMatchScore(call, it) }.thenBy(::localVersionMs))
            if (local != null) {
                claimedNotes += claimedNoteKey(local)
                result[keyFor(call)] = HomeCallNote(
                    text = local.note,
                    updatedAtMs = localVersionMs(local),
                    fromServer = false,
                    companyId = local.companyId.trim(),
                    serverClientEventId = local.serverClientEventId.trim().ifBlank { local.clientNoteId.trim() },
                )
                return@forEach
            }

            // Restored older calllog.notes files may not contain the v2 "type"
            // marker, so the direct lookup still recovers the note.
            val directNote = ContactNoteReader.callNoteForPhone(context, call.number, call.startedAt, call.direction)
            if (directNote.isNotBlank()) {
                result[keyFor(call)] = HomeCallNote(
                    text = directNote,
                    updatedAtMs = call.startedAt,
                    fromServer = false,
                    companyId = LocalNotesFileStore.companyIdForCall(context, call.number, call.startedAt, call.direction),
                    serverClientEventId = LocalNotesFileStore.clientNoteIdForCall(
                        call.number,
                        call.startedAt,
                        call.direction,
                    ),
                )
            }
        }
        return result
    }

    /** Keeps one independent latest note for Local and for every company. */
    fun mergeWithServer(
        calls: List<PhoneCallRecord>,
        localNotes: Map<String, HomeCallNote>,
        serverEvents: List<CallReportHistoryEvent>,
        principal: CallReportHistoryPrincipal = CallReportHistoryPrincipal(),
    ): Map<String, HomeCallNote> {
        if (calls.isEmpty()) return emptyMap()
        val callsByPhone = calls
            .filterNot { it.isSms }
            .groupBy { HomeCallPageLoader.noteKey(it.number) }
        val notesByCallAndScope = linkedMapOf<String, LinkedHashMap<String, HomeCallNote>>()

        calls.filterNot { it.isSms }.forEach { call ->
            val key = keyFor(call)
            localNotes[key]
                ?.expandedNotes()
                ?.filter { it.text.isNotBlank() && !it.fromServer }
                ?.forEach { note -> putLatest(notesByCallAndScope, key, scopeKey(note), note) }
        }

        serverEvents.forEach { event ->
            if (!event.communicationType.equals("note", ignoreCase = true)) return@forEach
            if (CallReportServerNoteClassifier.isExplicitGeneralNote(event)) return@forEach
            val candidates = callsByPhone[HomeCallPageLoader.noteKey(event.phone)].orEmpty()
            val call = candidates
                .filter { candidate -> sameServerCall(candidate, event) }
                .minByOrNull { candidate -> abs(candidate.startedAt - event.occurredAtMs) }
                ?: return@forEach
            if (!canAttachServerNoteToCall(event)) return@forEach

            val callKey = keyFor(call)
            val companyId = event.companyId.trim()
            val scope = if (companyId.isBlank()) UNSCOPED_SERVER_SCOPE else "company:$companyId"
            val version = serverVersionMs(event)
            val localMirror = notesByCallAndScope[callKey]
                ?.get(LOCAL_SCOPE)
                ?.takeIf { local -> isLocalServerMirror(local, event, principal) }

            if (event.note.isBlank()) {
                val mirrorIdMatches = localMirror != null && sameEventId(localMirror, event)
                val removalScope = if (mirrorIdMatches) LOCAL_SCOPE else scope
                val current = notesByCallAndScope[callKey]?.get(removalScope)
                if (current == null || version >= current.updatedAtMs) {
                    notesByCallAndScope[callKey]?.remove(removalScope)
                }
                return@forEach
            }

            if (localMirror != null) {
                val serverText = event.note.trim()
                notesByCallAndScope.getOrPut(callKey) { linkedMapOf() }[LOCAL_SCOPE] = localMirror.copy(
                    text = if (version >= localMirror.updatedAtMs) serverText else localMirror.text,
                    updatedAtMs = maxOf(localMirror.updatedAtMs, version),
                    serverClientEventId = event.clientEventId.trim().ifBlank {
                        localMirror.serverClientEventId
                    },
                    editable = !CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal),
                    relatedNotes = emptyList(),
                )
                return@forEach
            }

            val candidate = HomeCallNote(
                text = event.note.trim(),
                updatedAtMs = version,
                fromServer = true,
                authorName = event.authorBrokerName.trim(),
                companyId = companyId,
                serverClientEventId = event.clientEventId.trim(),
                editable = !CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal),
            )
            putLatest(notesByCallAndScope, callKey, scope, candidate)
        }

        return buildMap {
            calls.filterNot { it.isSms }.forEach { call ->
                val key = keyFor(call)
                val notes = notesByCallAndScope[key].orEmpty().values
                    .filter { it.text.isNotBlank() }
                    .sortedWith(noteDisplayOrder)
                if (notes.isEmpty()) return@forEach
                val primary = notes.first()
                put(key, primary.copy(relatedNotes = notes.drop(1)))
            }
        }
    }

    fun keyFor(call: PhoneCallRecord): String =
        "${HomeCallPageLoader.noteKey(call.number)}|${call.startedAt}|${call.direction.trim()}"

    private fun putLatest(
        target: MutableMap<String, LinkedHashMap<String, HomeCallNote>>,
        callKey: String,
        scope: String,
        note: HomeCallNote,
    ) {
        val bucket = target.getOrPut(callKey) { linkedMapOf() }
        val current = bucket[scope]
        if (current == null || isNewer(note, current)) bucket[scope] = note.copy(relatedNotes = emptyList())
    }

    private fun scopeKey(note: HomeCallNote): String {
        val companyId = note.companyId.trim()
        return when {
            companyId.isNotBlank() -> "company:$companyId"
            note.fromServer -> UNSCOPED_SERVER_SCOPE
            else -> LOCAL_SCOPE
        }
    }

    private fun isLocalServerMirror(
        local: HomeCallNote,
        event: CallReportHistoryEvent,
        principal: CallReportHistoryPrincipal,
    ): Boolean {
        if (local.fromServer || local.companyId.isNotBlank() || event.companyId.isNotBlank()) return false
        if (CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal)) return false
        if (sameEventId(local, event)) return true
        return normalizeNote(local.text) == normalizeNote(event.note) && local.text.isNotBlank()
    }

    private fun sameEventId(local: HomeCallNote, event: CallReportHistoryEvent): Boolean {
        val localId = local.serverClientEventId.trim()
        val eventId = event.clientEventId.trim()
        return localId.isNotBlank() && eventId.isNotBlank() && localId == eventId
    }

    private fun sameLocalCall(call: PhoneCallRecord, note: ContactCallNote): Boolean {
        if (call.startedAt <= 0L || note.callAt <= 0L) return false
        if (abs(call.startedAt - note.callAt) > LOCAL_NOTE_CALL_MATCH_WINDOW_MS) return false
        return call.direction.isBlank() || note.direction.isBlank() || call.direction == note.direction
    }

    private fun localMatchScore(call: PhoneCallRecord, note: ContactCallNote): Long = -abs(call.startedAt - note.callAt)

    private fun sameServerCall(call: PhoneCallRecord, event: CallReportHistoryEvent): Boolean {
        if (call.startedAt <= 0L || event.occurredAtMs <= 0L) return false
        if (abs(call.startedAt - event.occurredAtMs) > SERVER_NOTE_CALL_MATCH_WINDOW_MS) return false
        return call.direction.isBlank() || event.direction.isBlank() || call.direction == event.direction
    }

    private fun canAttachServerNoteToCall(event: CallReportHistoryEvent): Boolean {
        if (CallReportServerNoteClassifier.isExplicitGeneralNote(event)) return false
        return event.occurredAtMs > 0L && (
            CallReportServerNoteClassifier.isConcreteCallNote(event) ||
                event.companyId.isNotBlank() ||
                event.note.isNotBlank()
            )
    }

    private fun claimedNoteKey(note: ContactCallNote): String =
        note.clientNoteId.ifBlank { "${note.callAt}|${note.direction}|${note.note.hashCode()}" }

    private fun localVersionMs(note: ContactCallNote): Long = maxOf(note.savedAt, note.callAt)

    private fun serverVersionMs(event: CallReportHistoryEvent): Long = maxOf(
        event.updatedAtMs,
        event.createdAtMs,
        event.occurredAtMs,
    )

    private fun normalizeNote(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun isNewer(candidate: HomeCallNote, current: HomeCallNote): Boolean =
        candidate.updatedAtMs > current.updatedAtMs ||
            (candidate.updatedAtMs == current.updatedAtMs && candidate.fromServer && !current.fromServer)

    private val noteDisplayOrder = compareBy<HomeCallNote> {
        when {
            !it.fromServer && it.companyId.isBlank() -> 0
            it.editable -> 1
            else -> 2
        }
    }.thenBy { it.companyId.lowercase() }
        .thenByDescending { it.updatedAtMs }

    private const val LOCAL_SCOPE = "local"
    private const val UNSCOPED_SERVER_SCOPE = "server"
    private const val LOCAL_NOTE_CALL_MATCH_WINDOW_MS = 5 * 60 * 1000L
    private const val SERVER_NOTE_CALL_MATCH_WINDOW_MS = 10 * 60 * 1000L
}
