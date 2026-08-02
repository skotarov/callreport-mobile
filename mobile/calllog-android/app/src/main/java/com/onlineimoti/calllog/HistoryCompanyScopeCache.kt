package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Persists the last company scope returned successfully to the History screen.
 *
 * The cache is profile-scoped rather than token-scoped so a renewed access token
 * does not make the last verified company list disappear while the phone is offline.
 */
internal object HistoryCompanyScopeCache {
    private const val PREFS = "relationship_manager_history_company_scope_v1"
    private const val KEY_SCOPE = "profile_scope"
    private const val KEY_COMPANIES = "companies"
    private const val KEY_UPDATED_AT = "updated_at_ms"

    fun save(
        context: Context,
        config: AppConfig,
        companies: List<CallReportHistoryCompany>,
    ) {
        val scope = scopeFor(context.applicationContext, config) ?: return
        val payload = JSONArray().apply {
            companies
                .asSequence()
                .filter { company -> company.id.isNotBlank() }
                .distinctBy { company -> company.id }
                .sortedBy { company -> company.name.lowercase() }
                .forEach { company ->
                    put(JSONObject().apply {
                        put("id", company.id)
                        put("name", company.name.ifBlank { company.id })
                    })
                }
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCOPE, scope)
            .putString(KEY_COMPANIES, payload.toString())
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .commit()
    }

    /** Null means that this profile has never stored a successful History scope. */
    fun read(context: Context, config: AppConfig): List<CallReportHistoryCompany>? {
        val scope = scopeFor(context.applicationContext, config) ?: return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SCOPE, "").orEmpty() != scope) return null
        if (!prefs.contains(KEY_COMPANIES)) return null
        val raw = prefs.getString(KEY_COMPANIES, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    CallReportHistoryCompany(
                        id = id,
                        name = item.optString("name").trim().ifBlank { id },
                    ),
                )
            }
        }.distinctBy { company -> company.id }
            .sortedBy { company -> company.name.lowercase() }
    }

    private fun scopeFor(context: Context, config: AppConfig): String? {
        val baseUrl = config.baseUrl.trim().trimEnd('/').lowercase()
        if (baseUrl.isBlank()) return null
        val stableProfile = CompanySessionStore.profileScopeKey(context).ifBlank {
            CompanySessionStore.loadStored(context)?.let { snapshot ->
                snapshot.userEmail.trim().lowercase()
                    .ifBlank { PhoneNormalizer.key(snapshot.userPhone) }
            }.orEmpty()
        }
        if (stableProfile.isBlank()) return null
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$baseUrl|$stableProfile".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}
