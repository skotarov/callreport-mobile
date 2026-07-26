package com.onlineimoti.calllog

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Profile-owned CRM marker cache with durable server synchronization. */
internal object CrmContactSyncStore {
    private const val LEGACY_PREFS = "crm_contact_sync"
    private const val PROFILE_PREFS_PREFIX = "crm_contact_sync_profile_"
    private const val PENDING_PREFS_PREFIX = "crm_contact_sync_pending_"
    private const val META_PREFS = "crm_contact_sync_meta"
    private const val KEY_LEGACY_OWNER = "legacy_owner_v1"
    private const val KEY_LAST_REFRESH_PREFIX = "last_refresh_"
    private const val REFRESH_TTL_MS = 30_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val syncLock = Any()

    fun isEnabled(context: Context, phone: String): Boolean {
        val key = phoneKey(phone)
        val scope = profileScope(context.applicationContext)
        if (key.isBlank() || scope.isBlank()) return false
        return cachePrefs(context.applicationContext, scope).getBoolean(key, false)
    }

    /** Returns only the signed-in profile's enabled CRM phones. */
    fun enabledPhoneKeys(context: Context): Set<String> {
        val appContext = context.applicationContext
        val scope = profileScope(appContext)
        if (scope.isBlank()) return emptySet()
        return enabledKeys(cachePrefs(appContext, scope))
    }

    fun setEnabled(context: Context, phone: String, enabled: Boolean) {
        val appContext = context.applicationContext
        val key = phoneKey(phone)
        if (key.isBlank()) return
        val scope = profileScope(appContext)
        if (scope.isBlank()) {
            // Compatibility staging for a change made before a complete profile
            // session exists. It is claimed by the first signed-in profile only.
            appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, enabled).apply()
            return
        }

        val cache = cachePrefs(appContext, scope)
        val previous = cache.getBoolean(key, false)
        cache.edit().apply {
            if (enabled) putBoolean(key, true) else remove(key)
        }.apply()
        pendingPrefs(appContext, scope).edit().putBoolean(key, enabled).apply()
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

    /**
     * Runs on a worker thread. Pending local changes are sent first; any remaining
     * changes are then overlaid on the downloaded server snapshot.
     */
    fun refreshFromServer(context: Context, force: Boolean = false): Boolean {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        val scope = profileScope(appContext)
        if (scope.isBlank() || !CallReportRemoteAccess.isReady(config)) return false

        return synchronized(syncLock) {
            claimLegacyMarkers(appContext, scope)
            val pendingAtStart = pendingChanges(appContext, scope)
            val now = System.currentTimeMillis()
            if (!force && pendingAtStart.isEmpty() && now - lastRefresh(appContext, scope) < REFRESH_TTL_MS) {
                return@synchronized true
            }

            var serverPhones: Set<String>? = null
            if (pendingAtStart.isNotEmpty()) {
                serverPhones = runCatching {
                    ProfileCrmContactsClient.update(
                        context = appContext,
                        config = config,
                        changes = pendingAtStart.map { (phone, active) ->
                            ProfileCrmContactsClient.Change(phone, active)
                        },
                    )
                }.getOrNull()
                if (serverPhones != null) clearSentPending(appContext, scope, pendingAtStart)
            }

            if (serverPhones == null) {
                serverPhones = runCatching { ProfileCrmContactsClient.fetch(appContext, config) }.getOrNull()
            }
            if (serverPhones == null) return@synchronized false

            val effective = overlay(serverPhones, pendingChanges(appContext, scope))
            replaceCache(appContext, scope, effective)
            setLastRefresh(appContext, scope, System.currentTimeMillis())
            true
        }
    }

    private fun profileScope(context: Context): String = CompanySessionStore.profileScopeKey(context)

    private fun claimLegacyMarkers(context: Context, scope: String) {
        val scopeHash = hash(scope)
        val meta = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val owner = meta.getString(KEY_LEGACY_OWNER, "").orEmpty()
        if (owner.isNotBlank() && owner != scopeHash) return

        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val enabled = enabledKeys(legacy)
        if (enabled.isNotEmpty()) {
            val cacheEditor = cachePrefs(context, scope).edit()
            val pendingEditor = pendingPrefs(context, scope).edit()
            enabled.forEach { key ->
                cacheEditor.putBoolean(key, true)
                pendingEditor.putBoolean(key, true)
            }
            cacheEditor.commit()
            pendingEditor.commit()
        }
        meta.edit().putString(KEY_LEGACY_OWNER, scopeHash).commit()
        legacy.edit().clear().commit()
    }

    private fun pendingChanges(context: Context, scope: String): Map<String, Boolean> {
        return pendingPrefs(context, scope).all.mapNotNull { (rawKey, rawValue) ->
            val key = phoneKey(rawKey)
            val value = rawValue as? Boolean
            if (key.isBlank() || value == null) null else key to value
        }.toMap()
    }

    private fun clearSentPending(context: Context, scope: String, sent: Map<String, Boolean>) {
        val prefs = pendingPrefs(context, scope)
        val editor = prefs.edit()
        sent.forEach { (key, value) ->
            if ((prefs.all[key] as? Boolean) == value) editor.remove(key)
        }
        editor.commit()
    }

    private fun overlay(server: Set<String>, pending: Map<String, Boolean>): Set<String> {
        val result = server.mapTo(linkedSetOf(), ::phoneKey).filterTo(linkedSetOf()) { it.isNotBlank() }
        pending.forEach { (phone, active) ->
            if (active) result.add(phone) else result.remove(phone)
        }
        return result
    }

    private fun replaceCache(context: Context, scope: String, enabled: Set<String>) {
        val editor = cachePrefs(context, scope).edit().clear()
        enabled.map(::phoneKey).filter { it.isNotBlank() }.forEach { editor.putBoolean(it, true) }
        editor.commit()
    }

    private fun enabledKeys(prefs: SharedPreferences): Set<String> = prefs.all
        .asSequence()
        .filter { it.value as? Boolean == true }
        .map { phoneKey(it.key) }
        .filter { it.isNotBlank() }
        .toSet()

    private fun cachePrefs(context: Context, scope: String): SharedPreferences =
        context.getSharedPreferences(PROFILE_PREFS_PREFIX + hash(scope), Context.MODE_PRIVATE)

    private fun pendingPrefs(context: Context, scope: String): SharedPreferences =
        context.getSharedPreferences(PENDING_PREFS_PREFIX + hash(scope), Context.MODE_PRIVATE)

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
