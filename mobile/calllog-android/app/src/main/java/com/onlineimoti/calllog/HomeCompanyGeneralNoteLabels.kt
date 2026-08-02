package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.Executors

internal data class HomeCompanyScopeLabel(
    val companyId: String,
    val companyName: String,
    val hasGeneralNote: Boolean,
    val phase: Int,
    /** Latest visible company-scoped main note; blank when the label is phase-only. */
    val generalNote: String = "",
)

internal data class HomeCompanyScopeSnapshot(
    val labelsByPhoneKey: Map<String, List<HomeCompanyScopeLabel>> = emptyMap(),
    val serverBackedPhoneKeys: Set<String> = emptySet(),
)

/**
 * Creates compact company labels below a contact name in Home.
 * A yellow label means there is a company main note; a gray label means there
 * is no main note but the contact has a phase in that company.
 */
internal object HomeCompanyGeneralNoteLabels {
    fun fetch(context: Context, config: AppConfig, phones: List<String>): HomeCompanyScopeSnapshot {
        if (!CallReportRemoteAccess.isReady(config)) return HomeCompanyScopeSnapshot()

        val requestedPhones = phones
            .filter { HomeCallPageLoader.noteKey(it).isNotBlank() }
            .distinctBy { HomeCallPageLoader.noteKey(it) }
            .take(MAX_VISIBLE_PHONE_LOOKUPS)
        if (requestedPhones.isEmpty()) return HomeCompanyScopeSnapshot()

        val requestedPhoneKeys = requestedPhones.mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it) }
        val result = CallReportHistoryLookupClient.lookupMany(
            config = config,
            phones = requestedPhones,
            context = context.applicationContext,
        )
        val serverBackedPhoneKeys = result.events
            .asSequence()
            .filter { event ->
                event.communicationType.equals("note", ignoreCase = true) &&
                    event.note.trim().isNotBlank()
            }
            .mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.phone) }
            .filterTo(linkedSetOf()) { it.isNotBlank() && it in requestedPhoneKeys }
        val companiesById = result.principal.companies.associate { it.id to it.name }
        val labelsByPhone = linkedMapOf<String, LinkedHashMap<String, MutableScopeLabel>>()
        val confirmedGeneralScopes = linkedSetOf<String>()

        fun labelFor(phoneKey: String, companyId: String): MutableScopeLabel {
            val companyName = companiesById[companyId].orEmpty().ifBlank { companyId }
            return labelsByPhone
                .getOrPut(phoneKey) { linkedMapOf() }
                .getOrPut(companyId) { MutableScopeLabel(companyId, companyName) }
        }

        for (event in result.events) {
            if (event.note.isBlank() || event.companyId.isBlank()) continue
            val phoneKey = HomeCallPageLoader.noteKey(event.phone)
            if (phoneKey.isBlank() || phoneKey !in requestedPhoneKeys) continue

            if (CallReportServerNoteClassifier.isExplicitGeneralNote(event)) {
                confirmedGeneralScopes += scopeKey(phoneKey, event.companyId)
                labelFor(phoneKey, event.companyId).setServerGeneralNote(
                    text = event.note,
                    changedAtMs = maxOf(event.updatedAtMs, event.occurredAtMs, event.createdAtMs),
                )
            }
        }

        // Upload acknowledgement can arrive before lookup.php exposes the new row.
        // Keep the timestamped local cache briefly so the note never disappears.
        val nowMs = System.currentTimeMillis()
        for (phone in requestedPhones) {
            val phoneKey = HomeCallPageLoader.noteKey(phone)
            if (phoneKey.isBlank()) continue
            for (company in result.principal.companies) {
                val localNote = CallReportCompanyGeneralNoteStore.noteFor(context, phone, company.id)
                val decision = CompanyGeneralNoteCachePolicy.decide(
                    localNote = localNote,
                    pending = CallReportCompanyGeneralNotePending.isPending(context, phone, company.id),
                    savedAtMs = CallReportCompanyGeneralNoteStore.savedAtMsFor(context, phone, company.id),
                    nowMs = nowMs,
                    serverConfirmed = scopeKey(phoneKey, company.id) in confirmedGeneralScopes,
                )
                when (decision) {
                    CompanyGeneralNoteCacheDecision.SHOW_LOCAL -> {
                        labelFor(phoneKey, company.id).setLocalGeneralNote(localNote)
                    }
                    CompanyGeneralNoteCacheDecision.CLEAR_LOCAL -> {
                        CallReportCompanyGeneralNoteStore.saveOrDelete(context, phone, company.id, "")
                    }
                    CompanyGeneralNoteCacheDecision.IGNORE_LOCAL -> Unit
                }
            }
        }

        val phaseRequests = requestedPhones.flatMap { phone ->
            result.principal.companies.map { company -> PhaseRequest(phone, company.id) }
        }
        if (phaseRequests.isNotEmpty()) {
            val executor = Executors.newFixedThreadPool(minOf(MAX_PARALLEL_PHASE_LOOKUPS, phaseRequests.size))
            try {
                val futures = phaseRequests.map { request ->
                    executor.submit(Callable {
                        val hasLocalCompanyState = CompanyNegotiationPhaseStore.hasSavedState(
                            context,
                            request.phone,
                            request.companyId,
                        )
                        val localState = if (hasLocalCompanyState) {
                            CompanyNegotiationPhaseStore.state(context, request.phone, request.companyId)
                        } else {
                            ContactNegotiationPhaseState()
                        }
                        val remoteState = runCatching {
                            CompanyNegotiationPhaseRemoteClient.fetch(config, request.phone, request.companyId)
                        }.getOrNull()
                        val resolvedState = when {
                            remoteState == null -> localState
                            remoteState.updatedAtMs >= localState.updatedAtMs -> remoteState
                            else -> localState
                        }
                        PhaseResult(
                            phoneKey = HomeCallPageLoader.noteKey(request.phone),
                            companyId = request.companyId,
                            phase = resolvedState.phase,
                        )
                    })
                }
                futures.forEach { future ->
                    val phase = runCatching { future.get() }.getOrNull() ?: return@forEach
                    if (phase.phoneKey.isBlank() || phase.phase !in 1..4) return@forEach
                    labelFor(phase.phoneKey, phase.companyId).phase = phase.phase
                }
            } finally {
                executor.shutdownNow()
            }
        }

        val labelsByPhoneKey = labelsByPhone.mapValues { (_, labelsByCompany) ->
            labelsByCompany.values
                .filter { it.hasGeneralNote || it.phase in 1..4 }
                .sortedBy { it.companyName.lowercase() }
                .map { label ->
                    HomeCompanyScopeLabel(
                        companyId = label.companyId,
                        companyName = label.companyName,
                        hasGeneralNote = label.hasGeneralNote,
                        phase = label.phase,
                        generalNote = label.generalNote,
                    )
                }
        }.filterValues { it.isNotEmpty() }

        return HomeCompanyScopeSnapshot(
            labelsByPhoneKey = labelsByPhoneKey,
            serverBackedPhoneKeys = serverBackedPhoneKeys,
        )
    }

    private fun scopeKey(phoneKey: String, companyId: String) = "$phoneKey|${companyId.trim()}"

    private data class MutableScopeLabel(
        val companyId: String,
        val companyName: String,
        var hasGeneralNote: Boolean = false,
        var phase: Int = ContactNegotiationPhaseStore.NONE,
        var generalNote: String = "",
        private var generalNoteChangedAtMs: Long = 0L,
    ) {
        fun setServerGeneralNote(text: String, changedAtMs: Long) {
            val value = text.trim()
            if (value.isBlank()) return
            hasGeneralNote = true
            if (generalNote.isBlank() || changedAtMs >= generalNoteChangedAtMs) {
                generalNote = ServerNoteVisuals.prefixed(value)
                generalNoteChangedAtMs = changedAtMs
            }
        }

        fun setLocalGeneralNote(text: String) {
            val value = text.trim()
            if (value.isBlank()) return
            hasGeneralNote = true
            generalNote = value
            generalNoteChangedAtMs = Long.MAX_VALUE
        }
    }

    private data class PhaseRequest(
        val phone: String,
        val companyId: String,
    )

    private data class PhaseResult(
        val phoneKey: String,
        val companyId: String,
        val phase: Int,
    )

    private const val MAX_VISIBLE_PHONE_LOOKUPS = 20
    private const val MAX_PARALLEL_PHASE_LOOKUPS = 4
}
