package com.onlineimoti.calllog

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Profile-owned CRM marker cache with local-first, durable server synchronization. */
internal object CrmContactSyncStore {
    private const val PROFILE_PREFS_PREFIX = "crm_contact_sync_profile_"
    private const val PENDING_PREFS_PREFIX = "crm_contact_sync_pending_"
    private const val RECORD_PREFS_PREFIX = "crm_contact_sync_records_v2_"
    private const val PENDING_RECORD_PREFS_PREFIX = "crm_contact_sync_pending_v2_"
    private const val META_PREFS = "crm_contact_sync_meta"
    private const val KEY_LAST_REFRESH_PREFIX = "last_refresh_"
    private const val KEY_MIGRATED_PREFIX = "records_v2_migrated_"
    private const val REFRESH_TTL_MS = 30_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val syncLock = Any()
    private val storageLock = Any()

    fun isEnabled(context: Context, phone: String): Boolean {
        val key = phoneKey(phone)
        if (key.isBlank()) return false
        val appContext = context.applicationContext
        val scope = profileScope(appContext)
        if (scope.isBlank()) return false
        return synchronized(storageLock) { readRecords(appContext, scope)[key]?.active == true }
    }

    fun enabledPhoneKeys(context: Context): Set<String> {
        val appContext = context.applicationContext
        val scope = profileScope(appContext)
        if (scope.isBlank()) return emptySet()
        return synchronized(storageLock) {
            readRecords(appContext, scope).values
                .asSequence()
                .filter { it.active }
                .map { phoneKey(it.phone) }
                .filter { it.isNotBlank() }
                .toCollection(linkedSetOf())
        }
    }

    fun activeRecords(context: Context): List<CrmSyncRecord> {
        val appContext = context.applicationContext
        val scope = profileScope(appContext)
        if (scope.isBlank()) return emptyList()
        return synchronized(storageLock) {
            readRecords(appContext, scope).values.filter { it.active }
        }
    }

    fun setEnabled(context: Context, phone: String, enabled: Boolean) {
        val appContext = context.applicationContext
        val key = phoneKey(phone)
        val scope = profileScope(appContext)
        if (key.isBlank() || scope.isBlank()) return

        val marker = CrmSyncRecord(
            phone = key,
            active = enabled,
            updatedAtMs = System.currentTimeMillis(),
        )
        val previous = synchronized(storageLock) {
            val records = readRecords(appContext, scope).toMutableMap()
            val old = records[key]?.active == true
            records[key] = marker
            writeRecords(appContext, scope, records)

            val pending = readPending(appContext, scope).toMutableMap()
            pending[key] = marker
            writePending(appContext, scope, pending)
            old
        }
        if (previous != enabled) HomeCrmCompanyMembershipStore.invalidate(appContext, phone)
        refreshAsync(appContext, force = true)
    }

    fun toggle(context: Context, phone: String): Boolean {
        val enabled = !isEnabled(context, phone)
        setEnabled(context, phone, enabled)
        return enabled
    }

    fun refreshAsync(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        executor.execute { refreshFromServer(appContext, force) }
    }

    /** Runs on a worker thread and preserves unsent local changes on failure. */
    fun refreshFromServer(context: Context, force: Boolean = false): Boolean {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        val scope = profileScope(appContext)
        if (scope.isBlank() || !CallReportRemoteAccess.isReady(config)) return false

        return synchronized(syncLock) {
            val initial = synchronized(storageLock) {
                readRecords(appContext, scope) to readPending(appContext, scope)
            }
            val now = System.currentTimeMillis()
            if (!force && initial.second.isEmpty() && now - lastRefresh(appContext, scope) < REFRESH_TTL_MS) {
                return@synchronized true
            }

            val serverSnapshot = runCatching {
                ProfileCrmContactsClient.fetchSnapshot(appContext, config)
            }.getOrNull() ?: return@synchronized false

            val firstPlan = CrmContactSyncMerger.plan(
                localRecords = initial.first,
                pendingRecords = initial.second,
                serverRecords = serverRecords(serverSnapshot),
                serverIncludesInactive = serverSnapshot.includesInactive,
            )
            synchronized(storageLock) { applyPlan(appContext, scope, firstPlan) }

            var finalSnapshot = serverSnapshot
            if (firstPlan.outgoingRecords.isNotEmpty()) {
                finalSnapshot = runCatching {
                    ProfileCrmContactsClient.updateSnapshot(
                        context = appContext,
                        config = config,
                        changes = firstPlan.outgoingRecords.map { record ->
                            ProfileCrmContactsClient.Change(
                                phone = record.phone,
                                active = record.active,
                                updatedAtMs = record.updatedAtMs,
                            )
                        },
                    )
                }.getOrNull() ?: return@synchronized false
                synchronized(storageLock) {
                    clearSentPending(appContext, scope, firstPlan.outgoingRecords)
                }
            }

            val afterUpload = synchronized(storageLock) {
                readRecords(appContext, scope) to readPending(appContext, scope)
            }
            val finalPlan = CrmContactSyncMerger.plan(
                localRecords = afterUpload.first,
                pendingRecords = afterUpload.second,
                serverRecords = serverRecords(finalSnapshot),
                serverIncludesInactive = finalSnapshot.includesInactive,
            )
            synchronized(storageLock) { applyPlan(appContext, scope, finalPlan) }
            setLastRefresh(appContext, scope, System.currentTimeMillis())
            true
        }
    }

    private fun serverRecords(snapshot: ProfileCrmContactsClient.Snapshot): Map<String, CrmSyncRecord> =
        snapshot.recordsByPhoneKey.mapValues { (key, record) ->
            CrmSyncRecord(
                phone = record.phone.ifBlank { key },
                active = record.active,
                updatedAtMs = record.updatedAtMs,
            )
        }

    private fun applyPlan(context: Context, scope: String, plan: CrmSyncPlan) {
        writeRecords(context, scope, plan.effectiveRecords)
        val pending = readPending(context, scope).toMutableMap()
        plan.pendingKeysToClear.forEach(pending::remove)
        plan.outgoingRecords.forEach { record -> pending[phoneKey(record.phone)] = record }
        writePending(context, scope, pending)
    }

    private fun clearSentPending(context: Context, scope: String, sent: List<CrmSyncRecord>) {
        val pending = readPending(context, scope).toMutableMap()
        sent.forEach { record ->
            val key = phoneKey(record.phone)
            val current = pending[key]
            if (current?.active == record.active && current.updatedAtMs == record.updatedAtMs) pending.remove(key)
        }
        writePending(context, scope, pending)
    }

    private fun profileScope(context: Context): String {
        val stableScope = CompanySessionStore.profileScopeKey(context)
        val tokenScope = tokenScope(context)
        if (stableScope.isNotBlank() && tokenScope.isNotBlank() && stableScope != tokenScope) {
            synchronized(storageLock) { migrateScope(context, tokenScope, stableScope) }
        }
        return stableScope.ifBlank { tokenScope }
    }

    /** Moves edits made before the profile snapshot loaded into the stable profile namespace. */
    private fun migrateScope(context: Context, sourceScope: String, targetScope: String) {
        ensureLegacyMigrated(context, sourceScope)
        ensureLegacyMigrated(context, targetScope)

        val targetRecords = readRecordsRaw(context, targetScope).toMutableMap()
        readRecordsRaw(context, sourceScope).forEach { (key, source) ->
            val target = targetRecords[key]
            if (target == null || source.updatedAtMs >= target.updatedAtMs) targetRecords[key] = source
        }
        val targetPending = readPendingRaw(context, targetScope).toMutableMap()
        readPendingRaw(context, sourceScope).forEach { (key, source) ->
            val target = targetPending[key]
            if (target == null || source.updatedAtMs >= target.updatedAtMs) targetPending[key] = source
        }
        writeRecords(context, targetScope, targetRecords)
        writePending(context, targetScope, targetPending)
        setLastRefresh(context, targetScope, maxOf(lastRefresh(context, targetScope), lastRefresh(context, sourceScope)))

        recordPrefs(context, sourceScope).edit().clear().commit()
        pendingRecordPrefs(context, sourceScope).edit().clear().commit()
        cachePrefs(context, sourceScope).edit().clear().commit()
        legacyPendingPrefs(context, sourceScope).edit().clear().commit()
    }

    private fun tokenScope(context: Context): String {
        val accessToken = ConfigStore.load(context).accessToken.trim()
        return if (accessToken.isBlank()) "" else "token:${hash(accessToken)}"
    }

    private fun readRecords(context: Context, scope: String): Map<String, CrmSyncRecord> {
        ensureLegacyMigrated(context, scope)
        return readRecordsRaw(context, scope)
    }

    private fun readPending(context: Context, scope: String): Map<String, CrmSyncRecord> {
        ensureLegacyMigrated(context, scope)
        return readPendingRaw(context, scope)
    }

    private fun ensureLegacyMigrated(context: Context, scope: String) {
        val migrationKey = KEY_MIGRATED_PREFIX + hash(scope)
        val meta = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        if (meta.getBoolean(migrationKey, false)) return

        val records = readRecordsRaw(context, scope).toMutableMap()
        val pending = readPendingRaw(context, scope).toMutableMap()
        cachePrefs(context, scope).all.forEach { (rawKey, rawValue) ->
            val key = phoneKey(rawKey)
            if (key.isBlank() || rawValue as? Boolean != true || records.containsKey(key)) return@forEach
            val migrated = CrmSyncRecord(phone = key, active = true, updatedAtMs = 0L)
            records[key] = migrated
            pending.putIfAbsent(key, migrated)
        }
        legacyPendingPrefs(context, scope).all.forEach { (rawKey, rawValue) ->
            val key = phoneKey(rawKey)
            val active = rawValue as? Boolean ?: return@forEach
            if (key.isBlank() || pending.containsKey(key)) return@forEach
            pending[key] = CrmSyncRecord(phone = key, active = active, updatedAtMs = 0L)
        }
        writeRecords(context, scope, records)
        writePending(context, scope, pending)
        meta.edit().putBoolean(migrationKey, true).commit()
    }

    private fun readRecordsRaw(context: Context, scope: String): Map<String, CrmSyncRecord> =
        decodeRecords(recordPrefs(context, scope))

    private fun readPendingRaw(context: Context, scope: String): Map<String, CrmSyncRecord> =
        decodeRecords(pendingRecordPrefs(context, scope))

    private fun decodeRecords(prefs: SharedPreferences): Map<String, CrmSyncRecord> = buildMap {
        prefs.all.forEach { (rawKey, rawValue) ->
            val key = phoneKey(rawKey)
            val encoded = rawValue as? String
            if (key.isBlank() || encoded.isNullOrBlank()) return@forEach
            val parts = encoded.split('|', limit = 2)
            val active = when (parts.firstOrNull()) {
                "1" -> true
                "0" -> false
                else -> return@forEach
            }
            val updatedAt = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            put(key, CrmSyncRecord(phone = key, active = active, updatedAtMs = updatedAt))
        }
    }

    private fun writeRecords(context: Context, scope: String, records: Map<String, CrmSyncRecord>) {
        val editor = recordPrefs(context, scope).edit().clear()
        records.forEach { (rawKey, record) ->
            val key = phoneKey(rawKey.ifBlank { record.phone })
            if (key.isNotBlank()) editor.putString(key, encode(record))
        }
        editor.commit()

        // Keep the old active-only cache current for backward-compatible readers.
        val legacy = cachePrefs(context, scope).edit().clear()
        records.forEach { (rawKey, record) ->
            val key = phoneKey(rawKey.ifBlank { record.phone })
            if (key.isNotBlank() && record.active) legacy.putBoolean(key, true)
        }
        legacy.commit()
    }

    private fun writePending(context: Context, scope: String, records: Map<String, CrmSyncRecord>) {
        val editor = pendingRecordPrefs(context, scope).edit().clear()
        records.forEach { (rawKey, record) ->
            val key = phoneKey(rawKey.ifBlank { record.phone })
            if (key.isNotBlank()) editor.putString(key, encode(record))
        }
        editor.commit()
        legacyPendingPrefs(context, scope).edit().clear().commit()
    }

    private fun encode(record: CrmSyncRecord): String =
        "${if (record.active) 1 else 0}|${record.updatedAtMs.coerceAtLeast(0L)}"

    private fun cachePrefs(context: Context, scope: String): SharedPreferences =
        context.getSharedPreferences(PROFILE_PREFS_PREFIX + hash(scope), Context.MODE_PRIVATE)

    private fun legacyPendingPrefs(context: Context, scope: String): SharedPreferences =
        context.getSharedPreferences(PENDING_PREFS_PREFIX + hash(scope), Context.MODE_PRIVATE)

    private fun recordPrefs(context: Context, scope: String): SharedPreferences =
        context.getSharedPreferences(RECORD_PREFS_PREFIX + hash(scope), Context.MODE_PRIVATE)

    private fun pendingRecordPrefs(context: Context, scope: String): SharedPreferences =
        context.getSharedPreferences(PENDING_RECORD_PREFS_PREFIX + hash(scope), Context.MODE_PRIVATE)

    private fun lastRefresh(context: Context, scope: String): Long =
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_REFRESH_PREFIX + hash(scope), 0L)

    private fun setLastRefresh(context: Context, scope: String, value: Long) {
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_REFRESH_PREFIX + hash(scope), value).apply()
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun phoneKey(phone: String): String = PhoneNormalizer.key(phone)
}
