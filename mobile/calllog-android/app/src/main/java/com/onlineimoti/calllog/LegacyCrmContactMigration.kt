package com.onlineimoti.calllog

import android.content.Context
import java.security.MessageDigest

/**
 * Imports the pre-profile CRM switches into the signed-in profile exactly once.
 *
 * Older app versions stored every "CRM / грижа се за него" switch in the global
 * `crm_contact_sync` preferences. The profile-owned sync introduced later used a
 * different namespace, so those existing switches were otherwise invisible.
 */
internal object LegacyCrmContactMigration {
    private const val LEGACY_PREFS = "crm_contact_sync"
    private const val META_PREFS = "crm_contact_sync_meta"
    private const val KEY_MIGRATED_PREFIX = "legacy_global_profile_migrated_v1_"

    /**
     * Runs on the existing Clients worker thread.
     * Returns true only when this call uploaded missing legacy markers.
     * Failed migrations are left unmarked and retried later.
     */
    fun migrateIfNeeded(context: Context, config: AppConfig): Boolean {
        val appContext = context.applicationContext
        if (!CallReportRemoteAccess.isReady(config)) return false

        val profileScope = CompanySessionStore.profileScopeKey(appContext)
        if (profileScope.isBlank()) return false

        val migrationKey = KEY_MIGRATED_PREFIX + hash(profileScope)
        val meta = appContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        if (meta.getBoolean(migrationKey, false)) return false

        val legacyPhones = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .all
            .asSequence()
            .filter { (_, value) -> value as? Boolean == true }
            .map { (phone, _) -> PhoneNormalizer.key(phone) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        var uploaded = false
        if (legacyPhones.isNotEmpty()) {
            val snapshot = runCatching {
                ProfileCrmContactsClient.fetchSnapshot(appContext, config)
            }.getOrNull() ?: return false

            // Never revive a number that already has an active or inactive profile
            // record. Only genuinely missing legacy switches are imported.
            val missing = legacyPhones.filterNot(snapshot.recordsByPhoneKey::containsKey)
            if (missing.isNotEmpty()) {
                uploaded = runCatching {
                    ProfileCrmContactsClient.updateSnapshot(
                        context = appContext,
                        config = config,
                        changes = missing.map { phone ->
                            ProfileCrmContactsClient.Change(
                                phone = phone,
                                active = true,
                            )
                        },
                    )
                }.isSuccess
                if (!uploaded) return false
            }
        }

        if (!meta.edit().putBoolean(migrationKey, true).commit()) return false
        return uploaded
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
