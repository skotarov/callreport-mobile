package com.onlineimoti.calllog

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Moves CRM marker data from the profile namespaces used before stable user IDs
 * to the current authenticated user's stable user-ID namespace.
 *
 * Proven profile aliases (current email and phone) are always safe to migrate.
 * The oldest unscoped `crm_contact_sync` store has no embedded owner, so it is
 * claimed at most once by the first stable authenticated profile on this app
 * installation. The claim owner is persisted before another profile can reuse
 * those legacy markers, and imported markers are queued for server sync.
 */
internal object CrmContactProfileScopeMigration {
    private const val LEGACY_UNSCOPED_PREFS = "crm_contact_sync"
    private const val META_PREFS = "crm_contact_sync_meta"
    private const val KEY_LEGACY_OWNER = "legacy_owner_v1"
    private const val PROFILE_PREFS_PREFIX = "crm_contact_sync_profile_"
    private const val PENDING_PREFS_PREFIX = "crm_contact_sync_pending_"
    private const val RECORD_PREFS_PREFIX = "crm_contact_sync_records_v2_"
    private const val PENDING_RECORD_PREFS_PREFIX = "crm_contact_sync_pending_v2_"

    private val lock = Any()

    private data class StoredRecord(
        val phone: String,
        val active: Boolean,
        val updatedAtMs: Long,
    )

    /**
     * Returns the number of CRM states newly adopted into the stable user-ID
     * namespace. This is safe to call repeatedly; migrated source namespaces
     * are cleared only after the target writes succeed.
     */
    fun migrateKnownAliases(context: Context): Int {
        val appContext = context.applicationContext
        val session = CompanySessionStore.load(appContext) ?: return 0
        val targetScope = session.userId.trim()
        if (targetScope.isBlank()) return 0

        return synchronized(lock) {
            var migrated = claimLegacyUnscopedMarkers(appContext, targetScope)

            // Before userId became the preferred profile scope, email was preferred
            // over phone. Process phone first and email second so an equally old
            // email-side explicit edit remains the better legacy fallback.
            val sourceScopes = linkedSetOf<String>().apply {
                PhoneNormalizer.key(session.userPhone)
                    .takeIf { it.isNotBlank() && it != targetScope }
                    ?.let(::add)
                session.userEmail.trim().lowercase()
                    .takeIf { it.isNotBlank() && it != targetScope }
                    ?.let(::add)
            }
            if (sourceScopes.isNotEmpty()) {
                migrated += migrateScopes(appContext, sourceScopes.toList(), targetScope)
            }
            migrated
        }
    }

    /**
     * Older app versions stored the hand/person CRM marker without a profile
     * namespace. There is no historical owner field to recover, so the only
     * safe deterministic migration is a one-time claim by the first stable
     * authenticated profile on this installation. Existing user-scoped state
     * always wins, and imported active markers are also written to pending so
     * the normal CrmContactSyncStore flow uploads them to that user's server CRM.
     */
    private fun claimLegacyUnscopedMarkers(context: Context, targetScope: String): Int {
        val scopeHash = hash(targetScope)
        val meta = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val existingOwner = meta.getString(KEY_LEGACY_OWNER, "").orEmpty()
        if (existingOwner.isNotBlank() && existingOwner != scopeHash) return 0

        val legacy = context.getSharedPreferences(LEGACY_UNSCOPED_PREFS, Context.MODE_PRIVATE)
        val legacyActive = linkedSetOf<String>()
        legacy.all.forEach { (rawKey, rawValue) ->
            val key = PhoneNormalizer.key(rawKey)
            if (key.isNotBlank() && rawValue as? Boolean == true) legacyActive += key
        }

        if (legacyActive.isEmpty()) {
            // Record the owner even for an empty old store. That prevents a later
            // profile on the same installation from ever claiming unowned data.
            if (existingOwner.isBlank()) meta.edit().putString(KEY_LEGACY_OWNER, scopeHash).commit()
            return 0
        }

        val targetRecords = readRecords(context, targetScope).toMutableMap()
        val targetPending = readPending(context, targetScope).toMutableMap()
        val adopted = linkedMapOf<String, StoredRecord>()

        legacyActive.forEach { key ->
            // A stable-profile state is authoritative over timestamp-less legacy
            // data, including an explicit inactive state/tombstone.
            if (targetRecords.containsKey(key) || targetPending.containsKey(key)) return@forEach
            val record = StoredRecord(phone = key, active = true, updatedAtMs = 0L)
            targetRecords[key] = record
            targetPending[key] = record
            adopted[key] = record
        }

        if (adopted.isNotEmpty()) {
            val recordEditor = recordPrefs(context, targetScope).edit()
            val pendingEditor = pendingRecordPrefs(context, targetScope).edit()
            val activeEditor = profilePrefs(context, targetScope).edit()
            adopted.forEach { (key, record) ->
                recordEditor.putString(key, encode(record))
                pendingEditor.putString(key, encode(record))
                activeEditor.putBoolean(key, true)
            }
            if (!recordEditor.commit() || !pendingEditor.commit() || !activeEditor.commit()) return 0
        }

        // Persist the claimant before deleting the only ownerless copy. If the
        // metadata write fails, leave legacy intact so migration can retry.
        if (!meta.edit().putString(KEY_LEGACY_OWNER, scopeHash).commit()) return 0
        if (!legacy.edit().clear().commit()) return 0
        return adopted.size
    }

    private fun migrateScopes(context: Context, sourceScopes: List<String>, targetScope: String): Int {
        val sourceEffective = linkedMapOf<String, StoredRecord>()
        var hasSourceData = false

        sourceScopes.forEach { sourceScope ->
            val sourceRecords = readRecords(context, sourceScope)
            val sourcePending = readPending(context, sourceScope)
            if (sourceRecords.isNotEmpty() || sourcePending.isNotEmpty()) hasSourceData = true

            sourceRecords.forEach { (key, record) ->
                mergeSource(sourceEffective, key, record, preferIncomingOnEqual = false)
            }
            // Pending is an explicit local edit, so it wins an equal-timestamp
            // legacy cache value from the same/older alias.
            sourcePending.forEach { (key, record) ->
                mergeSource(sourceEffective, key, record, preferIncomingOnEqual = true)
            }
        }
        if (!hasSourceData || sourceEffective.isEmpty()) return 0

        val targetRecords = readRecords(context, targetScope).toMutableMap()
        val targetPending = readPending(context, targetScope).toMutableMap()
        val changed = linkedMapOf<String, StoredRecord>()

        sourceEffective.forEach { (key, source) ->
            val target = targetRecords[key]
            // Current userId-scoped data wins ties. Legacy aliases may only
            // replace a missing or strictly older current-profile state.
            if (target != null && source.updatedAtMs <= target.updatedAtMs) return@forEach
            targetRecords[key] = source
            changed[key] = source

            val pending = targetPending[key]
            if (pending == null || source.updatedAtMs > pending.updatedAtMs) {
                // Re-submit the migrated state. A newer server tombstone/state
                // still wins in CrmContactSyncMerger, including timestamp-less
                // migrated markers.
                targetPending[key] = source
            }
        }

        if (changed.isNotEmpty()) {
            val recordEditor = recordPrefs(context, targetScope).edit()
            val pendingEditor = pendingRecordPrefs(context, targetScope).edit()
            val legacyActiveEditor = profilePrefs(context, targetScope).edit()

            changed.forEach { (key, record) ->
                recordEditor.putString(key, encode(record))
                val pending = targetPending[key]
                if (pending != null) pendingEditor.putString(key, encode(pending))
                if (record.active) legacyActiveEditor.putBoolean(key, true)
                else legacyActiveEditor.remove(key)
            }

            val recordsSaved = recordEditor.commit()
            val pendingSaved = pendingEditor.commit()
            val legacySaved = legacyActiveEditor.commit()
            if (!recordsSaved || !pendingSaved || !legacySaved) return 0
        }

        // The aliases belong to this same authenticated profile, and the target
        // now contains every state that was newer than its current counterpart.
        sourceScopes.forEach { sourceScope ->
            recordPrefs(context, sourceScope).edit().clear().commit()
            pendingRecordPrefs(context, sourceScope).edit().clear().commit()
            profilePrefs(context, sourceScope).edit().clear().commit()
            pendingPrefs(context, sourceScope).edit().clear().commit()
        }

        return changed.size
    }

    private fun mergeSource(
        destination: MutableMap<String, StoredRecord>,
        key: String,
        incoming: StoredRecord,
        preferIncomingOnEqual: Boolean,
    ) {
        val current = destination[key]
        if (
            current == null ||
            incoming.updatedAtMs > current.updatedAtMs ||
            (preferIncomingOnEqual && incoming.updatedAtMs == current.updatedAtMs)
        ) {
            destination[key] = incoming
        }
    }

    private fun readRecords(context: Context, scope: String): Map<String, StoredRecord> {
        val result = decodeRecords(recordPrefs(context, scope)).toMutableMap()
        profilePrefs(context, scope).all.forEach { (rawKey, rawValue) ->
            val key = PhoneNormalizer.key(rawKey)
            if (key.isBlank() || rawValue as? Boolean != true || result.containsKey(key)) return@forEach
            result[key] = StoredRecord(phone = key, active = true, updatedAtMs = 0L)
        }
        return result
    }

    private fun readPending(context: Context, scope: String): Map<String, StoredRecord> {
        val result = decodeRecords(pendingRecordPrefs(context, scope)).toMutableMap()
        pendingPrefs(context, scope).all.forEach { (rawKey, rawValue) ->
            val key = PhoneNormalizer.key(rawKey)
            val active = rawValue as? Boolean ?: return@forEach
            if (key.isBlank() || result.containsKey(key)) return@forEach
            result[key] = StoredRecord(phone = key, active = active, updatedAtMs = 0L)
        }
        return result
    }

    private fun decodeRecords(prefs: SharedPreferences): Map<String, StoredRecord> = buildMap {
        prefs.all.forEach { (rawKey, rawValue) ->
            val key = PhoneNormalizer.key(rawKey)
            val encoded = rawValue as? String
            if (key.isBlank() || encoded.isNullOrBlank()) return@forEach
            val parts = encoded.split('|', limit = 2)
            val active = when (parts.firstOrNull()) {
                "1" -> true
                "0" -> false
                else -> return@forEach
            }
            val updatedAtMs = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            put(key, StoredRecord(phone = key, active = active, updatedAtMs = updatedAtMs))
        }
    }

    private fun encode(record: StoredRecord): String =
        "${if (record.active) 1 else 0}|${record.updatedAtMs.coerceAtLeast(0L)}"

    private fun profilePrefs(context: Context, scope: String): SharedPreferences =
        context.getSharedPreferences(PROFILE_PREFS_PREFIX + hash(scope), Context.MODE_PRIVATE)

    private fun pendingPrefs(context: Context, scope: String): SharedPreferences =
        context.getSharedPreferences(PENDING_PREFS_PREFIX + hash(scope), Context.MODE_PRIVATE)

    private fun recordPrefs(context: Context, scope: String): SharedPreferences =
        context.getSharedPreferences(RECORD_PREFS_PREFIX + hash(scope), Context.MODE_PRIVATE)

    private fun pendingRecordPrefs(context: Context, scope: String): SharedPreferences =
        context.getSharedPreferences(PENDING_RECORD_PREFS_PREFIX + hash(scope), Context.MODE_PRIVATE)

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
