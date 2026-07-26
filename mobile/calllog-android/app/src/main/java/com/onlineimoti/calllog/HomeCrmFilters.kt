package com.onlineimoti.calllog

import android.content.Context
import android.content.SharedPreferences

/**
 * CRM Home filters. Selecting one or more phases shows those phases; with no
 * phase button selected, only records without an assigned phase are shown.
 */
internal data class HomeCrmFilterState(
    val phases: Set<Int> = emptySet(),
    val companyIds: Set<String> = emptySet(),
) {
    val hasSelectedPhase: Boolean get() = phases.isNotEmpty()
    val hasPhaseFilter: Boolean get() = true
    val hasCompanyFilter: Boolean get() = companyIds.isNotEmpty()
    val isActive: Boolean get() = true

    /**
     * When no phase is selected, the company membership lookup still narrows
     * unphased records to the selected firm(s).
     */
    val isCompanyFiltered: Boolean get() = hasCompanyFilter && !hasSelectedPhase
}

/** Keeps the CRM selections after the app is reopened. */
internal object HomeCrmFilterStore {
    enum class Scope { CRM_CALLS, CLIENTS }

    private const val PREFS = "relationship_manager_prefs"
    private const val KEY_PHASES = "home_crm_phase_filters_v2"
    private const val KEY_COMPANIES = "home_crm_company_filters_v1"
    private const val KEY_CLIENTS_PHASES = "home_crm_clients_phase_filters_v1"
    private const val KEY_CLIENTS_COMPANIES = "home_crm_clients_company_filters_v1"
    private const val LEGACY_KEY_PHASE = "home_crm_phase_filter"

    fun scopeForContactsMode(contactsMode: Boolean): Scope = if (contactsMode) Scope.CLIENTS else Scope.CRM_CALLS

    fun load(context: Context): HomeCrmFilterState = load(context, Scope.CRM_CALLS)

    fun load(context: Context, scope: Scope): HomeCrmFilterState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (scope == Scope.CLIENTS && !prefs.contains(KEY_CLIENTS_PHASES) && !prefs.contains(KEY_CLIENTS_COMPANIES)) {
            // First run after this change: keep the user's currently selected filters
            // instead of making the Clients page look reset.
            return loadFromKeys(prefs, KEY_PHASES, KEY_COMPANIES, readLegacyPhase = true)
        }
        return when (scope) {
            Scope.CRM_CALLS -> loadFromKeys(prefs, KEY_PHASES, KEY_COMPANIES, readLegacyPhase = true)
            Scope.CLIENTS -> loadFromKeys(prefs, KEY_CLIENTS_PHASES, KEY_CLIENTS_COMPANIES, readLegacyPhase = false)
        }
    }

    fun save(context: Context, state: HomeCrmFilterState) = save(context, state, Scope.CRM_CALLS)

    fun save(context: Context, state: HomeCrmFilterState, scope: Scope) {
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        when (scope) {
            Scope.CRM_CALLS -> editor
                .putStringSet(KEY_PHASES, normalizedPhaseStrings(state))
                .putStringSet(KEY_COMPANIES, normalizedCompanyIds(state))
                .remove(LEGACY_KEY_PHASE)
            Scope.CLIENTS -> editor
                .putStringSet(KEY_CLIENTS_PHASES, normalizedPhaseStrings(state))
                .putStringSet(KEY_CLIENTS_COMPANIES, normalizedCompanyIds(state))
        }.apply()
    }

    private fun loadFromKeys(
        prefs: SharedPreferences,
        phaseKey: String,
        companyKey: String,
        readLegacyPhase: Boolean,
    ): HomeCrmFilterState {
        val storedPhases = prefs.getStringSet(phaseKey, null)
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: if (readLegacyPhase) {
                legacyPhases(prefs.getInt(LEGACY_KEY_PHASE, ContactNegotiationPhaseStore.NONE))
            } else {
                emptySet()
            }
        val companies = prefs.getStringSet(companyKey, emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        return HomeCrmFilterState(
            phases = normalizePhases(storedPhases),
            companyIds = companies,
        )
    }

    private fun normalizedPhaseStrings(state: HomeCrmFilterState): LinkedHashSet<String> =
        normalizePhases(state.phases).mapTo(linkedSetOf()) { it.toString() }

    private fun normalizedCompanyIds(state: HomeCrmFilterState): Set<String> =
        state.companyIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()

    private fun legacyPhases(phase: Int): Set<Int> = if (
        phase in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4
    ) setOf(phase) else emptySet()

    private fun normalizePhases(phases: Set<Int>): Set<Int> = phases.filterTo(linkedSetOf()) {
        it in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4
    }
}

/** Filters CRM candidates before pagination. */
internal object HomeCrmFilterEngine {
    fun filterLocal(
        context: Context,
        calls: List<PhoneCallRecord>,
        state: HomeCrmFilterState,
    ): List<PhoneCallRecord> {
        if (calls.isEmpty()) return emptyList()
        val phasesByCompanyByPhone = HomeCrmPhaseLookup.resolveEffectiveCompanyPhases(
            context = context.applicationContext,
            config = ConfigStore.load(context.applicationContext),
            phones = calls.map { it.number },
        )
        return calls.filter { call ->
            val phasesByCompany = phasesByCompanyByPhone[HomeCallPageLoader.noteKey(call.number)]
                .orEmpty()
                .toMutableMap()

            // contacts_shared_lookup.php also returns a phase for personal records.
            // company_phase.php intentionally returns only company-scoped phases,
            // so retain this hint to avoid dropping personal clients from a phase.
            val serverHint = ServerCrmContactsPhaseHints.forPhone(call.number)
            if (serverHint != null && serverHint.phase in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4) {
                phasesByCompany.putIfAbsent(serverHint.companyId, serverHint.phase)
            }

            // A just-edited personal/legacy phase must win until its server round-trip
            // completes, just like CompanyNegotiationPhaseStore does for firm phases.
            if (serverHint?.companyId.isNullOrBlank()) {
                val localPersonal = ContactNegotiationPhaseStore.state(context.applicationContext, call.number)
                if (localPersonal.updatedAtMs > 0L) {
                    if (localPersonal.phase in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4) {
                        phasesByCompany[""] = localPersonal.phase
                    } else {
                        phasesByCompany.remove("")
                    }
                }
            }

            val scopedPhases = if (state.hasCompanyFilter) {
                phasesByCompany.filterKeys { it in state.companyIds }
            } else {
                phasesByCompany
            }
            if (state.hasSelectedPhase) {
                scopedPhases.values.any { phase -> phase in state.phases }
            } else {
                scopedPhases.values.none { phase ->
                    phase in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4
                }
            }
        }
    }

    /** Applies a firm-only filter from the durable relationship-history cache. */
    fun filterByCompany(
        calls: List<PhoneCallRecord>,
        state: HomeCrmFilterState,
        companyIdsByPhoneKey: Map<String, Set<String>>,
    ): List<PhoneCallRecord> {
        if (!state.isCompanyFiltered) return calls
        return calls.filter { call ->
            companyIdsByPhoneKey[HomeCallPageLoader.noteKey(call.number)]
                ?.any { it in state.companyIds }
                ?: false
        }
    }
}
