package com.onlineimoti.calllog

/** Timestamp helpers used by the Clients cache. Each field is merged independently. */
internal object ClientsObjectMerge {
    data class CrmState(val active: Boolean?, val updatedAtMs: Long)
    data class PhaseState(val phase: Int?, val updatedAtMs: Long)

    fun crm(local: CrmState, incoming: CrmState): CrmState = when {
        incoming.updatedAtMs <= 0L -> local
        local.updatedAtMs > incoming.updatedAtMs -> local
        else -> incoming
    }

    fun phase(local: PhaseState, incoming: PhaseState): PhaseState = when {
        incoming.updatedAtMs <= 0L -> local
        local.updatedAtMs > incoming.updatedAtMs -> local
        else -> incoming
    }

    fun noteUpdatedAt(existingUpdatedAtMs: Long, incomingUpdatedAtMs: Long): Boolean =
        incomingUpdatedAtMs.coerceAtLeast(0L) >= existingUpdatedAtMs.coerceAtLeast(0L)
}
