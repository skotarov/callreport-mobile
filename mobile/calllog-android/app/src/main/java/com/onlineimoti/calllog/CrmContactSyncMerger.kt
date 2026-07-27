package com.onlineimoti.calllog

/** One profile-owned CRM marker as known by a device or by the server. */
internal data class CrmSyncRecord(
    val phone: String,
    val active: Boolean,
    val updatedAtMs: Long,
)

internal data class CrmSyncPlan(
    val effectiveRecords: Map<String, CrmSyncRecord>,
    val outgoingRecords: List<CrmSyncRecord>,
    val pendingKeysToClear: Set<String>,
)

/**
 * Resolves profile CRM markers with last-write-wins semantics.
 *
 * Pending records are explicit local edits. A newer timestamp wins; when one
 * side has no timestamp, an explicit pending local edit wins over an old server
 * value, while a timestamped server value wins over migrated timestamp-less data.
 */
internal object CrmContactSyncMerger {
    fun plan(
        localRecords: Map<String, CrmSyncRecord>,
        pendingRecords: Map<String, CrmSyncRecord>,
        serverRecords: Map<String, CrmSyncRecord>,
        serverIncludesInactive: Boolean,
    ): CrmSyncPlan {
        val effective = linkedMapOf<String, CrmSyncRecord>()
        val outgoing = linkedMapOf<String, CrmSyncRecord>()
        val clearPending = linkedSetOf<String>()
        val keys = linkedSetOf<String>().apply {
            addAll(localRecords.keys)
            addAll(pendingRecords.keys)
            addAll(serverRecords.keys)
        }

        keys.forEach { key ->
            val pending = pendingRecords[key]
            val local = pending ?: localRecords[key]
            val server = serverRecords[key]

            if (server != null) {
                if (local == null) {
                    effective[key] = server
                    return@forEach
                }

                if (pending != null) {
                    when {
                        pending.active == server.active -> {
                            effective[key] = newerSameState(pending, server)
                            clearPending += key
                        }
                        isServerNewer(server, pending) -> {
                            effective[key] = server
                            clearPending += key
                        }
                        else -> {
                            effective[key] = pending
                            outgoing[key] = pending
                        }
                    }
                    return@forEach
                }

                when {
                    local.active == server.active -> effective[key] = newerSameState(local, server)
                    isServerNewerOrPreferred(server, local) -> effective[key] = server
                    else -> {
                        effective[key] = local
                        outgoing[key] = local
                    }
                }
                return@forEach
            }

            if (local == null) return@forEach
            if (pending != null) {
                if (pending.active) {
                    effective[key] = pending
                    outgoing[key] = pending
                } else {
                    // Active-only legacy snapshots express an inactive record by
                    // omitting it. The desired local state is therefore confirmed.
                    effective[key] = pending
                    clearPending += key
                }
                return@forEach
            }

            if (serverIncludesInactive) {
                // A complete v2 snapshot has no record for this key. Preserve a
                // timestamped local edit if its pending flag was lost and retry it.
                if (local.active && local.updatedAtMs > 0L) {
                    effective[key] = local
                    outgoing[key] = local
                } else if (!local.active) {
                    effective[key] = local
                }
            } else {
                // Legacy responses contain every active marker. Absence means the
                // profile is no longer CRM on the server, so remove stale local state.
                effective[key] = local.copy(active = false)
            }
        }

        return CrmSyncPlan(
            effectiveRecords = effective,
            outgoingRecords = outgoing.values.toList(),
            pendingKeysToClear = clearPending,
        )
    }

    private fun newerSameState(left: CrmSyncRecord, right: CrmSyncRecord): CrmSyncRecord =
        if (right.updatedAtMs > left.updatedAtMs) right else left

    private fun isServerNewer(server: CrmSyncRecord, local: CrmSyncRecord): Boolean = when {
        server.updatedAtMs > 0L && local.updatedAtMs <= 0L -> true
        server.updatedAtMs <= 0L && local.updatedAtMs > 0L -> false
        else -> server.updatedAtMs > local.updatedAtMs
    }

    private fun isServerNewerOrPreferred(server: CrmSyncRecord, local: CrmSyncRecord): Boolean = when {
        server.updatedAtMs > 0L && local.updatedAtMs <= 0L -> true
        server.updatedAtMs <= 0L && local.updatedAtMs > 0L -> false
        server.updatedAtMs == local.updatedAtMs -> true
        else -> server.updatedAtMs > local.updatedAtMs
    }
}
