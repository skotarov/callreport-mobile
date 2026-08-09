package com.onlineimoti.calllog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

internal data class CachedClientsPage(
    val data: HomeRenderData,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val savedAtMs: Long,
)

/**
 * Durable object-level Clients cache. The old rendered snapshot remains a
 * compatibility fast-path, while this database keeps client/state/note objects
 * independently so one newer field cannot erase unrelated state.
 */
internal class ClientsCacheRepository private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE clients(scope TEXT NOT NULL, client_key TEXT NOT NULL, phone TEXT NOT NULL, normalized_phone TEXT NOT NULL, name TEXT NOT NULL, last_activity_ms INTEGER NOT NULL, snapshot_updated_ms INTEGER NOT NULL, PRIMARY KEY(scope,client_key))")
        db.execSQL("CREATE TABLE page_meta(scope TEXT NOT NULL, signature TEXT NOT NULL, page_offset INTEGER NOT NULL, total INTEGER NOT NULL, page_limit INTEGER NOT NULL, saved_at_ms INTEGER NOT NULL, PRIMARY KEY(scope,signature,page_offset))")
        db.execSQL("CREATE TABLE page_item(scope TEXT NOT NULL, signature TEXT NOT NULL, page_offset INTEGER NOT NULL, position INTEGER NOT NULL, client_key TEXT NOT NULL, PRIMARY KEY(scope,signature,page_offset,position))")
        db.execSQL("CREATE TABLE company_membership(scope TEXT NOT NULL, client_key TEXT NOT NULL, company_id TEXT NOT NULL, PRIMARY KEY(scope,client_key,company_id))")
        db.execSQL("CREATE TABLE current_user_state(scope TEXT NOT NULL, client_key TEXT NOT NULL, crm_active INTEGER, crm_updated_ms INTEGER NOT NULL, phase INTEGER, phase_updated_ms INTEGER NOT NULL, PRIMARY KEY(scope,client_key))")
        db.execSQL("CREATE TABLE other_user_state(scope TEXT NOT NULL, client_key TEXT NOT NULL, user_id TEXT NOT NULL, display_name TEXT NOT NULL, crm_active INTEGER, crm_updated_ms INTEGER NOT NULL, phase INTEGER, phase_updated_ms INTEGER NOT NULL, PRIMARY KEY(scope,client_key,user_id))")
        db.execSQL("CREATE TABLE client_note(scope TEXT NOT NULL, client_key TEXT NOT NULL, note_id TEXT NOT NULL, author_id TEXT NOT NULL, author_name TEXT NOT NULL, company_id TEXT NOT NULL, text_value TEXT NOT NULL, created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL, editable INTEGER NOT NULL, PRIMARY KEY(scope,note_id))")
        db.execSQL("CREATE INDEX page_item_client_idx ON page_item(scope,client_key)")
        db.execSQL("CREATE INDEX note_client_idx ON client_note(scope,client_key,updated_at_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun loadPage(
        config: AppConfig,
        filterState: HomeCrmFilterState,
        searchQuery: String,
        limit: Int,
        offset: Int,
    ): CachedClientsPage? {
        val scope = scope(config)
        if (scope.isBlank()) return null
        val signature = signature(filterState, searchQuery)
        val meta = readableDatabase.rawQuery(
            "SELECT total,page_limit,saved_at_ms FROM page_meta WHERE scope=? AND signature=? AND page_offset=?",
            arrayOf(scope, signature, offset.coerceAtLeast(0).toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else Triple(cursor.getInt(0), cursor.getInt(1), cursor.getLong(2))
        } ?: return null
        val calls = mutableListOf<PhoneCallRecord>()
        val names = linkedMapOf<String, String>()
        val contactNotes = linkedMapOf<String, String>()
        readableDatabase.rawQuery(
            "SELECT c.client_key,c.phone,c.name,c.last_activity_ms FROM page_item p JOIN clients c ON c.scope=p.scope AND c.client_key=p.client_key WHERE p.scope=? AND p.signature=? AND p.page_offset=? ORDER BY p.position ASC",
            arrayOf(scope, signature, offset.coerceAtLeast(0).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val clientKey = cursor.getString(0)
                val phone = cursor.getString(1)
                val name = cursor.getString(2)
                calls += PhoneCallRecord(phone, name, "", cursor.getLong(3), 0L)
                val phoneKey = HomeCallPageLoader.noteKey(phone)
                names[phoneKey] = name
                latestNote(scope, clientKey)?.let { contactNotes[phoneKey] = it }
            }
        }
        return CachedClientsPage(
            data = HomeRenderData(calls, contactNotes, names, emptyMap()),
            total = meta.first,
            limit = meta.second.takeIf { it > 0 } ?: limit,
            offset = offset.coerceAtLeast(0),
            savedAtMs = meta.third,
        )
    }

    fun storePage(
        context: Context,
        config: AppConfig,
        filterState: HomeCrmFilterState,
        searchQuery: String,
        page: ServerCrmContactsPage,
    ) {
        val scope = scope(config)
        if (scope.isBlank()) return
        val signature = signature(filterState, searchQuery)
        val now = System.currentTimeMillis()
        val canonicalCrm = CrmContactSyncStore.records(context.applicationContext)
            .associateBy { PhoneNormalizer.key(it.phone) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("page_item", "scope=? AND signature=? AND page_offset=?", arrayOf(scope, signature, page.offset.toString()))
            page.clients.forEachIndexed { position, client ->
                val key = client.identity.ifBlank { client.normalizedPhone }
                putClient(db, scope, key, client, now)
                putCompanies(db, scope, key, client.companyIds)
                putCurrentState(
                    db = db,
                    scope = scope,
                    key = key,
                    client = client,
                    canonicalCrm = canonicalCrm[client.normalizedPhone],
                    effectivePhase = effectivePhase(context, client),
                )
                client.userStates.forEach { state -> putOtherUserState(db, scope, key, state) }
                client.notes.forEach { note -> putNote(db, scope, key, note) }
                db.insertWithOnConflict("page_item", null, ContentValues().apply {
                    put("scope", scope); put("signature", signature); put("page_offset", page.offset)
                    put("position", position); put("client_key", key)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.insertWithOnConflict("page_meta", null, ContentValues().apply {
                put("scope", scope); put("signature", signature); put("page_offset", page.offset)
                put("total", page.total); put("page_limit", page.limit); put("saved_at_ms", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun putClient(db: SQLiteDatabase, scope: String, key: String, client: ServerCrmClient, now: Long) {
        db.insertWithOnConflict("clients", null, ContentValues().apply {
            put("scope", scope); put("client_key", key); put("phone", client.phone)
            put("normalized_phone", client.normalizedPhone); put("name", client.name)
            put("last_activity_ms", client.lastActivityAtMs); put("snapshot_updated_ms", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun putCompanies(db: SQLiteDatabase, scope: String, key: String, companyIds: Set<String>) {
        db.delete("company_membership", "scope=? AND client_key=?", arrayOf(scope, key))
        companyIds.forEach { companyId ->
            db.insertWithOnConflict("company_membership", null, ContentValues().apply {
                put("scope", scope); put("client_key", key); put("company_id", companyId)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    private fun putCurrentState(
        db: SQLiteDatabase,
        scope: String,
        key: String,
        client: ServerCrmClient,
        canonicalCrm: CrmSyncRecord?,
        effectivePhase: ContactNegotiationPhaseState,
    ) {
        val existing = readCurrentState(db, scope, key)
        val serverCrm = ClientsObjectMerge.CrmState(client.isCrm, client.crmUpdatedAtMs)
        val canonical = canonicalCrm?.let { ClientsObjectMerge.CrmState(it.active, it.updatedAtMs) }
        val crm = ClientsObjectMerge.crm(existing.first, canonical ?: serverCrm)
        val phase = ClientsObjectMerge.phase(existing.second, ClientsObjectMerge.PhaseState(effectivePhase.phase, effectivePhase.updatedAtMs))
        db.insertWithOnConflict("current_user_state", null, ContentValues().apply {
            put("scope", scope); put("client_key", key); putNullableBoolean("crm_active", crm.active)
            put("crm_updated_ms", crm.updatedAtMs); putNullableInt("phase", phase.phase); put("phase_updated_ms", phase.updatedAtMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun readCurrentState(db: SQLiteDatabase, scope: String, key: String): Pair<ClientsObjectMerge.CrmState, ClientsObjectMerge.PhaseState> =
        db.rawQuery("SELECT crm_active,crm_updated_ms,phase,phase_updated_ms FROM current_user_state WHERE scope=? AND client_key=?", arrayOf(scope, key)).use { cursor ->
            if (!cursor.moveToFirst()) {
                ClientsObjectMerge.CrmState(null, 0L) to ClientsObjectMerge.PhaseState(null, 0L)
            } else {
                val crm = ClientsObjectMerge.CrmState(if (cursor.isNull(0)) null else cursor.getInt(0) != 0, cursor.getLong(1))
                val phase = ClientsObjectMerge.PhaseState(if (cursor.isNull(2)) null else cursor.getInt(2), cursor.getLong(3))
                crm to phase
            }
        }

    private fun effectivePhase(context: Context, client: ServerCrmClient): ContactNegotiationPhaseState {
        val server = ContactNegotiationPhaseState(client.phase ?: ContactNegotiationPhaseStore.NONE, client.phaseUpdatedAtMs)
        return if (server.updatedAtMs > 0L) ContactNegotiationPhaseStore.applyServerState(context, client.phone, server)
        else ContactNegotiationPhaseStore.state(context, client.phone)
    }

    private fun putOtherUserState(db: SQLiteDatabase, scope: String, key: String, incoming: ServerCrmUserState) {
        val existing = db.rawQuery("SELECT display_name,crm_active,crm_updated_ms,phase,phase_updated_ms FROM other_user_state WHERE scope=? AND client_key=? AND user_id=?", arrayOf(scope, key, incoming.userId)).use { cursor ->
            if (!cursor.moveToFirst()) null else ExistingOther(
                cursor.getString(0),
                ClientsObjectMerge.CrmState(if (cursor.isNull(1)) null else cursor.getInt(1) != 0, cursor.getLong(2)),
                ClientsObjectMerge.PhaseState(if (cursor.isNull(3)) null else cursor.getInt(3), cursor.getLong(4)),
            )
        }
        val crm = ClientsObjectMerge.crm(existing?.crm ?: ClientsObjectMerge.CrmState(null, 0L), ClientsObjectMerge.CrmState(incoming.crmActive, incoming.crmUpdatedAtMs))
        val phase = ClientsObjectMerge.phase(existing?.phase ?: ClientsObjectMerge.PhaseState(null, 0L), ClientsObjectMerge.PhaseState(incoming.phase, incoming.phaseUpdatedAtMs))
        db.insertWithOnConflict("other_user_state", null, ContentValues().apply {
            put("scope", scope); put("client_key", key); put("user_id", incoming.userId)
            put("display_name", incoming.displayName.ifBlank { existing?.name.orEmpty() })
            putNullableBoolean("crm_active", crm.active); put("crm_updated_ms", crm.updatedAtMs)
            putNullableInt("phase", phase.phase); put("phase_updated_ms", phase.updatedAtMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun putNote(db: SQLiteDatabase, scope: String, key: String, note: ServerCrmNote) {
        val existingUpdated = db.rawQuery("SELECT updated_at_ms FROM client_note WHERE scope=? AND note_id=?", arrayOf(scope, note.id)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        }
        if (!ClientsObjectMerge.noteUpdatedAt(existingUpdated, note.updatedAtMs)) return
        db.insertWithOnConflict("client_note", null, ContentValues().apply {
            put("scope", scope); put("client_key", key); put("note_id", note.id); put("author_id", note.authorId)
            put("author_name", note.authorName); put("company_id", note.companyId); put("text_value", note.text)
            put("created_at_ms", note.createdAtMs); put("updated_at_ms", note.updatedAtMs); put("editable", if (note.editable) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun latestNote(scope: String, key: String): String? = readableDatabase.rawQuery(
        "SELECT text_value,author_name FROM client_note WHERE scope=? AND client_key=? ORDER BY updated_at_ms DESC,created_at_ms DESC LIMIT 1",
        arrayOf(scope, key),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else {
            val text = cursor.getString(0).orEmpty()
            val author = cursor.getString(1).orEmpty().trim()
            if (author.isBlank()) text else "$author: $text"
        }
    }

    private fun scope(config: AppConfig): String {
        val token = config.accessToken.trim()
        if (token.isBlank()) return ""
        return sha256(config.baseUrl.trim().trimEnd('/') + "\n" + token)
    }

    private fun signature(state: HomeCrmFilterState, searchQuery: String): String = sha256(buildString {
        append("crm="); append(state.crmOnly); append("\nphase="); append(state.phases.sorted().joinToString(","))
        append("\ncompany="); append(state.companyIds.map(String::trim).filter(String::isNotBlank).sorted().joinToString(","))
        append("\nq="); append(searchQuery.trim().lowercase())
    })

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun ContentValues.putNullableBoolean(key: String, value: Boolean?) {
        if (value == null) putNull(key) else put(key, if (value) 1 else 0)
    }

    private fun ContentValues.putNullableInt(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private data class ExistingOther(val name: String, val crm: ClientsObjectMerge.CrmState, val phase: ClientsObjectMerge.PhaseState)

    companion object {
        private const val DB_NAME = "relationship_manager_clients.db"
        private const val DB_VERSION = 1
        @Volatile private var instance: ClientsCacheRepository? = null
        fun get(context: Context): ClientsCacheRepository = instance ?: synchronized(this) {
            instance ?: ClientsCacheRepository(context.applicationContext).also { instance = it }
        }
    }
}
