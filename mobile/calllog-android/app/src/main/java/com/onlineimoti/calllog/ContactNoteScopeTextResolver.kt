package com.onlineimoti.calllog

import android.content.Context
import kotlin.math.abs

internal data class ContactNoteScopeValue(
    val text: String = "",
    val serverClientEventId: String = "",
)

/**
 * Resolves the note and original server id that belong to the currently selected
 * Local/company scope. Call notes are independent for every company.
 */
internal object ContactNoteScopeTextResolver {
    fun cachedValue(context: Context, draft: ContactNoteFormDraft, companyId: String): ContactNoteScopeValue {
        return when {
            companyId == ContactNoteTopicState.LOCAL_COMPANY_ID && draft.isGeneralNote -> {
                ContactNoteScopeValue(ContactNoteReader.generalNoteForPhone(context, draft.phone))
            }
            companyId == ContactNoteTopicState.LOCAL_COMPANY_ID -> {
                ContactNoteScopeValue(ContactNoteReader.callNoteForPhone(context, draft.phone, draft.callAt, draft.direction))
            }
            draft.isGeneralNote -> {
                ContactNoteScopeValue(CallReportCompanyGeneralNoteStore.noteFor(context, draft.phone, companyId))
            }
            else -> {
                val pending = CompanyCallNoteOutbox.pendingEvents(context, listOf(draft.phone))
                    .filter { event ->
                        event.companyId == companyId &&
                            sameCall(draft, event)
                    }
                    .maxByOrNull { event -> maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs) }
                ContactNoteScopeValue(
                    text = pending?.note.orEmpty(),
                    serverClientEventId = pending?.clientEventId.orEmpty(),
                )
            }
        }
    }

    /**
     * Returns every currently available server note keyed by company id. Null means
     * the server is not available, so callers should preserve cached/initial text.
     */
    fun loadServerValues(context: Context, draft: ContactNoteFormDraft): Map<String, ContactNoteScopeValue>? {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        if (!CallReportRemoteAccess.isReady(config) || draft.phone.isBlank()) return null

        return if (draft.isGeneralNote) {
            val notes = CallReportCompanyGeneralNotesClient.fetch(appContext, config, draft.phone)
            val notesByCompany = linkedMapOf<String, ContactNoteScopeValue>()
            notes.groupBy { it.companyId }.forEach { (companyId, companyNotes) ->
                // A freshly edited server note is first written to the durable outbox
                // and local company cache. Until sync is confirmed, that pending value
                // is authoritative and an older server response must not overwrite it.
                if (CallReportTopicNoteOutbox.isGeneralPending(appContext, draft.phone, companyId)) {
                    notesByCompany[companyId] = cachedValue(appContext, draft, companyId)
                    return@forEach
                }

                val ownNote = companyNotes
                    .filter { it.editable && !it.placeholder }
                    .maxByOrNull { it.updatedAtMs }
                if (ownNote != null) {
                    notesByCompany[companyId] = ContactNoteScopeValue(
                        text = ownNote.note,
                        serverClientEventId = ownNote.clientEventId,
                    )
                    CallReportCompanyGeneralNoteStore.saveOrDelete(
                        appContext,
                        draft.phone,
                        companyId,
                        ownNote.note,
                    )
                } else if (companyNotes.any { it.multiAuthor }) {
                    // The company may contain colleagues' notes, but their text must
                    // never prefill this profile's editor or become locally editable.
                    notesByCompany[companyId] = ContactNoteScopeValue()
                }
            }
            notesByCompany
        } else {
            val history = CallReportHistoryLookupClient.lookup(config, draft.phone)
            val phoneKey = HomeCallPageLoader.noteKey(draft.phone)
            val latestByCompany = mutableMapOf<String, CallReportHistoryEvent>()
            history.events.forEach { event ->
                if (!event.communicationType.equals("note", ignoreCase = true)) return@forEach
                if (event.companyId.isBlank() || event.note.isBlank()) return@forEach
                if (HomeCallPageLoader.noteKey(event.phone) != phoneKey) return@forEach
                if (!sameCall(draft, event)) return@forEach
                val previous = latestByCompany[event.companyId]
                if (previous == null || versionMs(event) >= versionMs(previous)) {
                    latestByCompany[event.companyId] = event
                }
            }
            val serverValues = latestByCompany.mapValues { (_, event) ->
                ContactNoteScopeValue(
                    text = event.note,
                    serverClientEventId = event.clientEventId.trim(),
                )
            }
            val pendingValues = CompanyCallNoteOutbox.pendingEvents(appContext, listOf(draft.phone))
                .filter { event -> event.companyId.isNotBlank() && sameCall(draft, event) }
                .groupBy { event -> event.companyId }
                .mapValues { (_, events) ->
                    val event = events.maxByOrNull(::versionMs) ?: return@mapValues ContactNoteScopeValue()
                    ContactNoteScopeValue(
                        text = event.note,
                        serverClientEventId = event.clientEventId.trim(),
                    )
                }
            overlayPendingValues(serverValues, pendingValues)
        }
    }

    fun valueFor(
        companyId: String,
        draft: ContactNoteFormDraft,
        serverValues: Map<String, ContactNoteScopeValue>?,
        context: Context,
    ): ContactNoteScopeValue {
        if (companyId == ContactNoteTopicState.LOCAL_COMPANY_ID) {
            return cachedValue(context, draft, companyId)
        }
        return serverValues?.get(companyId) ?: cachedValue(context, draft, companyId)
    }

    internal fun overlayPendingValues(
        serverValues: Map<String, ContactNoteScopeValue>,
        pendingValues: Map<String, ContactNoteScopeValue>,
    ): Map<String, ContactNoteScopeValue> = LinkedHashMap(serverValues).apply {
        putAll(pendingValues)
    }

    // Compatibility helpers retained for older callers/tests.
    fun cachedText(context: Context, draft: ContactNoteFormDraft, companyId: String): String =
        cachedValue(context, draft, companyId).text

    fun loadServerTexts(context: Context, draft: ContactNoteFormDraft): Map<String, String>? =
        loadServerValues(context, draft)?.mapValues { it.value.text }

    fun textFor(companyId: String, draft: ContactNoteFormDraft, serverTexts: Map<String, String>?, context: Context): String {
        if (companyId == ContactNoteTopicState.LOCAL_COMPANY_ID) return cachedText(context, draft, companyId)
        return serverTexts?.get(companyId) ?: cachedText(context, draft, companyId)
    }

    private fun sameCall(draft: ContactNoteFormDraft, event: CallReportHistoryEvent): Boolean {
        if (draft.callAt <= 0L || event.occurredAtMs <= 0L) return false
        if (abs(draft.callAt - event.occurredAtMs) > CALL_MATCH_WINDOW_MS) return false
        return draft.direction.isBlank() || event.direction.isBlank() || event.direction == draft.direction
    }

    private fun versionMs(event: CallReportHistoryEvent): Long = maxOf(
        event.updatedAtMs,
        event.createdAtMs,
        event.occurredAtMs,
    )

    private const val CALL_MATCH_WINDOW_MS = 10 * 60 * 1000L
}
