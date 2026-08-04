package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** Data read from Android providers and local stores away from the UI thread. */
internal data class HistoryLocalSnapshot(
    val calls: List<PhoneCallRecord> = emptyList(),
    val latestCall: PhoneCallRecord? = null,
    val sms: List<SmsMessageRecord> = emptyList(),
    val callNotes: List<ContactCallNote> = emptyList(),
    val generalNote: String = "",
    val generalNotePending: Boolean = false,
    val contactExists: Boolean = false,
    val companyScopeAvailable: Boolean = false,
)

/** Fully prepared History content; the main thread only turns this state into Views. */
internal data class HistoryPreparedSnapshot(
    val rows: List<CallReportHistoryRow> = emptyList(),
    val fullLogEntries: List<FilteredFullLogEntry> = emptyList(),
    val companyMainNotes: List<CallReportCompanyMainNote> = emptyList(),
    val unscopedServerMainNote: CallReportHistoryEvent? = null,
    val hasCompanyMainNoteScope: Boolean = false,
    val confirmedLocalServerNote: Boolean = false,
)

internal object HistoryBackgroundLoader {
    private data class CachedHistoryCompanyScope(
        val context: Context,
        val companies: List<CallReportHistoryCompany>,
    )

    private val stagedCachedCompanyScopes = ConcurrentHashMap<String, CachedHistoryCompanyScope>()

    fun cachedLocal(context: Context, phone: String): HistoryLocalSnapshot? {
        val rawSnapshot = HistorySnapshotCache.readLocal(context.applicationContext, phone) ?: return null
        val crmEnabled = CrmContactSyncStore.isEnabled(context, phone)
        val unknownNumber = !crmEnabled && !rawSnapshot.contactExists
        val companyScopeAvailable = ContactServerCompanyScopePolicy.isAvailable(crmEnabled, unknownNumber)
        val snapshot = rawSnapshot.copy(companyScopeAvailable = companyScopeAvailable)
        val key = HomeCallPageLoader.noteKey(phone)
        if (key.isNotBlank()) {
            val companies = cachedHistoryCompanies(context)
            if (companies.isEmpty()) {
                stagedCachedCompanyScopes.remove(key)
            } else {
                stagedCachedCompanyScopes[key] = CachedHistoryCompanyScope(
                    context = context.applicationContext,
                    companies = companies,
                )
            }
        }
        return snapshot
    }

    fun loadLocal(context: Context, phone: String): HistoryLocalSnapshot {
        if (phone.isBlank()) return HistoryLocalSnapshot()

        // This may inspect the Android call log and write a resolved pending note,
        // so it must happen before the snapshot and never from render().
        PendingCallNoteStore.reconcilePendingForPhone(context, phone)

        val localTimeline = FilteredFullLogLoader.loadLocal(context, phone)
        val contactExists = hasRealContact(context, phone)
        val crmEnabled = CrmContactSyncStore.isEnabled(context, phone)
        val unknownNumber = !crmEnabled && !contactExists
        val snapshot = HistoryLocalSnapshot(
            calls = localTimeline.calls,
            latestCall = localTimeline.calls.firstOrNull(),
            sms = localTimeline.sms,
            callNotes = localTimeline.notes,
            generalNote = ContactNoteReader.generalNoteForPhone(context, phone),
            generalNotePending = CallReportDeferredCompanyAssignmentStore.isGeneralPending(context, phone),
            contactExists = contactExists,
            companyScopeAvailable = ContactServerCompanyScopePolicy.isAvailable(crmEnabled, unknownNumber),
        )
        HistorySnapshotCache.writeLocal(context.applicationContext, phone, snapshot)
        return snapshot
    }

    /** Fast cache-first presentation used before Android providers and the server are refreshed. */
    fun prepareCachedLocal(phone: String, snapshot: HistoryLocalSnapshot): HistoryPreparedSnapshot {
        if (phone.isBlank()) return HistoryPreparedSnapshot()
        val local = FilteredFullLogLocalData(
            calls = snapshot.calls,
            sms = snapshot.sms,
            notes = snapshot.callNotes,
        )
        val localRows = FilteredFullLogLoader.cachedLocalRows(phone, local)
        val key = HomeCallPageLoader.noteKey(phone)
        val cachedScope = if (key.isNotBlank()) {
            stagedCachedCompanyScopes.remove(key)
        } else {
            null
        }
        val cachedCompanyNotes = cachedScope?.let { scope ->
            companyMainNotes(
                context = scope.context,
                phone = phone,
                serverLoaded = false,
                companies = scope.companies,
                events = emptyList(),
            )
        }.orEmpty()
        val visibleCompanyNotes = CompanyMainNoteVisibilityPolicy.visibleNotes(
            companyScopeAvailable = snapshot.companyScopeAvailable,
            notes = cachedCompanyNotes,
        )
        return HistoryPreparedSnapshot(
            rows = localRows.filter { row -> row.kind != CallReportHistoryRowKind.PHONE },
            fullLogEntries = FilteredFullLogLoader.groupedEntries(localRows),
            companyMainNotes = visibleCompanyNotes,
            hasCompanyMainNoteScope = CompanyMainNoteVisibilityPolicy.shouldShow(
                companyScopeAvailable = snapshot.companyScopeAvailable,
                notes = visibleCompanyNotes,
            ),
        )
    }

    fun prepare(
        context: Context,
        phone: String,
        remoteEnabled: Boolean,
        serverLoaded: Boolean,
        history: CallReportHistoryLookupResult,
        localCalls: List<PhoneCallRecord>,
        localSms: List<SmsMessageRecord>,
        localNotes: List<ContactCallNote>,
    ): HistoryPreparedSnapshot {
        if (phone.isBlank()) return HistoryPreparedSnapshot()
        if (remoteEnabled && serverLoaded) reconcileServerConfirmation(context, phone, history)
        val scopedServerLoaded = remoteEnabled && serverLoaded
        val config = ConfigStore.load(context)
        if (scopedServerLoaded) {
            HistoryCompanyScopeCache.save(
                context = context.applicationContext,
                config = config,
                companies = history.principal.companies,
            )
        }
        val companyScopeAvailable = remoteEnabled && ContactServerCompanyScope.isAvailable(context, phone)
        // Existing server notes are readable even when this known contact is not
        // enrolled in CRM. CRM still controls empty lanes and creation controls.
        val scopedCompanies = when {
            scopedServerLoaded -> history.principal.companies
            else -> cachedHistoryCompanies(context, config)
        }
        val principal = if (remoteEnabled) history.principal else CallReportHistoryPrincipal()
        val pendingEvents = if (remoteEnabled) {
            CompanyCallNoteOutbox.pendingEvents(context.applicationContext, listOf(phone)) +
                CallReportNoteOutbox.pendingExistingServerEvents(context.applicationContext, listOf(phone))
        } else {
            emptyList()
        }
        val effectiveEvents = overlayPendingEvents(history.events, pendingEvents)
        val notesTimelineEvents = if (remoteEnabled) notesAndSms(effectiveEvents) else emptyList()
        val localTimeline = FilteredFullLogLocalData(
            calls = localCalls,
            sms = localSms,
            notes = localNotes,
        )
        val allCompanyMainNotes = companyMainNotes(
            context = context,
            phone = phone,
            serverLoaded = scopedServerLoaded,
            companies = scopedCompanies,
            events = history.events,
        )
        val visibleCompanyMainNotes = CompanyMainNoteVisibilityPolicy.visibleNotes(
            companyScopeAvailable = companyScopeAvailable,
            notes = allCompanyMainNotes,
        )
        return HistoryPreparedSnapshot(
            rows = CallReportHistoryMerge.merge(
                context = context,
                phone = phone,
                principal = principal,
                localCalls = emptyList(),
                localSms = localSms,
                localNotes = localNotes,
                serverEvents = notesTimelineEvents,
            ),
            fullLogEntries = FilteredFullLogLoader.prepare(
                context = context,
                phone = phone,
                remoteEnabled = remoteEnabled,
                principal = principal,
                local = localTimeline,
                serverEvents = effectiveEvents,
            ),
            companyMainNotes = visibleCompanyMainNotes,
            unscopedServerMainNote = unscopedServerMainNote(phone, scopedServerLoaded, history),
            hasCompanyMainNoteScope = CompanyMainNoteVisibilityPolicy.shouldShow(
                companyScopeAvailable = companyScopeAvailable,
                notes = visibleCompanyMainNotes,
            ),
            confirmedLocalServerNote = ServerRecordIndex.hasConfirmedNoteForPhone(context, phone, localNotes),
        )
    }

    private fun overlayPendingEvents(
        serverEvents: List<CallReportHistoryEvent>,
        pendingEvents: List<CallReportHistoryEvent>,
    ): List<CallReportHistoryEvent> {
        if (pendingEvents.isEmpty()) return serverEvents
        val merged = linkedMapOf<String, CallReportHistoryEvent>()
        (serverEvents + pendingEvents).forEach { event ->
            val key = event.clientEventId.trim()
                .ifBlank { event.serverId.trim() }
                .ifBlank {
                    listOf(
                        HomeCallPageLoader.noteKey(event.phone),
                        event.communicationType,
                        event.direction,
                        event.occurredAtMs.toString(),
                        event.companyId,
                    ).joinToString("|")
                }
            val current = merged[key]
            val changedAt = maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs)
            val currentChangedAt = current?.let {
                maxOf(it.updatedAtMs, it.createdAtMs, it.occurredAtMs)
            } ?: Long.MIN_VALUE
            if (current == null || changedAt >= currentChangedAt) merged[key] = event
        }
        return merged.values.sortedByDescending {
            maxOf(it.updatedAtMs, it.createdAtMs, it.occurredAtMs)
        }
    }

    private fun cachedHistoryCompanies(context: Context): List<CallReportHistoryCompany> =
        cachedHistoryCompanies(context, ConfigStore.load(context))

    private fun cachedHistoryCompanies(
        context: Context,
        config: AppConfig,
    ): List<CallReportHistoryCompany> {
        if (!CallReportRemoteAccess.isReady(config)) return emptyList()
        HistoryCompanyScopeCache.read(context.applicationContext, config)?.let { companies ->
            return companies
        }
        return CallReportTopicCompaniesCache.read(context.applicationContext, config)
            ?.companies
            .orEmpty()
            .asSequence()
            .filter { company -> company.id.isNotBlank() }
            .distinctBy { company -> company.id }
            .map { company ->
                CallReportHistoryCompany(
                    id = company.id,
                    name = company.name.ifBlank { company.id },
                )
            }
            .sortedBy { company -> company.name.lowercase() }
            .toList()
    }

    private fun reconcileServerConfirmation(
        context: Context,
        phone: String,
        history: CallReportHistoryLookupResult,
    ) {
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        val confirmedNoteIds = history.events.asSequence()
            .filter { event ->
                event.communicationType.equals("note", ignoreCase = true) &&
                    event.note.trim().isNotBlank() &&
                    HomeCallPageLoader.noteKey(event.phone) == phoneKey
            }
            .map { event -> event.clientEventId.trim() }
            .filter { id -> id.isNotBlank() }
            .toList()
        ServerRecordIndex.markConfirmed(context, history.events.map { it.clientEventId })
        ServerRecordIndex.reconcileConfirmedNotesForPhone(context, phone, confirmedNoteIds)
    }

    private fun companyMainNotes(
        context: Context,
        phone: String,
        serverLoaded: Boolean,
        companies: List<CallReportHistoryCompany>,
        events: List<CallReportHistoryEvent>,
    ): List<CallReportCompanyMainNote> {
        if (companies.isEmpty()) return emptyList()
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        val latestByCompany = mutableMapOf<String, CallReportHistoryEvent>()
        if (serverLoaded) {
            events.forEach { event ->
                if (!event.communicationType.equals("note", ignoreCase = true)) return@forEach
                if (event.companyId.isBlank() || HomeCallPageLoader.noteKey(event.phone) != phoneKey) return@forEach
                if (!CallReportServerNoteClassifier.isExplicitGeneralNote(event)) return@forEach
                val current = latestByCompany[event.companyId]
                if (current == null || event.updatedAtMs >= current.updatedAtMs) latestByCompany[event.companyId] = event
            }
        }
        return companies.map { company ->
            val remote = latestByCompany[company.id]
            val pending = CallReportCompanyGeneralNotePending.isPending(context, phone, company.id)
            val cached = CallReportCompanyGeneralNoteStore.noteFor(context, phone, company.id)
            if (serverLoaded && !pending && remote == null && cached.isNotBlank()) {
                CallReportCompanyGeneralNoteStore.saveOrDelete(context, phone, company.id, "")
            }
            val note = when {
                pending && cached.isNotBlank() -> cached
                remote != null -> remote.note
                !serverLoaded && cached.isNotBlank() -> cached
                else -> ""
            }
            CallReportCompanyMainNote(
                companyId = company.id,
                companyName = company.name,
                note = note,
                updatedAtMs = remote?.updatedAtMs ?: 0L,
                confirmedByServer = remote != null && !pending && remote.note.isNotBlank(),
                pending = pending,
            )
        }
    }

    private fun unscopedServerMainNote(
        phone: String,
        serverLoaded: Boolean,
        history: CallReportHistoryLookupResult,
    ): CallReportHistoryEvent? {
        if (!serverLoaded || phone.isBlank()) return null
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        if (phoneKey.isBlank()) return null
        return history.events
            .asSequence()
            .filter { event ->
                event.companyId.isBlank() &&
                    event.note.trim().isNotBlank() &&
                    HomeCallPageLoader.noteKey(event.phone) == phoneKey &&
                    CallReportServerNoteClassifier.isExplicitGeneralNote(event)
            }
            .maxByOrNull { event -> maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs) }
    }

    private fun notesAndSms(events: List<CallReportHistoryEvent>): List<CallReportHistoryEvent> =
        events.filter { event ->
            event.communicationType.equals("sms", ignoreCase = true) ||
                (event.communicationType.equals("note", ignoreCase = true) &&
                    event.note.trim().isNotBlank() &&
                    !CallReportServerNoteClassifier.isExplicitGeneralNote(event))
        }

    private fun hasRealContact(context: Context, phone: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return RmRealContactLookup.findContactId(context, phone) > 0L
    }
}
