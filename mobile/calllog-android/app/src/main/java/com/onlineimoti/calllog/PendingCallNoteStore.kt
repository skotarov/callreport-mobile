package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import kotlin.concurrent.thread

internal data class PendingCallNote(
    val phone: String,
    val direction: String,
    val sessionStartedAt: Long,
    val savedAt: Long,
    val note: String,
    /** Selected company for a call note that is waiting for the call-log row. */
    val companyId: String = "",
    val authorProfileId: String = "",
    val authorName: String = "",
)

internal object PendingCallNoteStore {
    private const val PREFS = "callreport_pending_call_notes"
    private const val CALL_LIFECYCLE_PREFS = "callreport_call_lifecycle"
    private const val KEY_ACTIVE = "active"
    private const val KEY_NUMBER = "number"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_STARTED_AT = "started_at"
    private const val MAX_PENDING_AGE_MS = 24 * 60 * 60 * 1000L
    private const val MATCH_BEFORE_SESSION_MS = 6 * 60 * 60 * 1000L
    private const val MATCH_AFTER_SAVE_MS = 5 * 60 * 1000L
    private val RETRY_DELAYS_MS = longArrayOf(0L, 1_000L, 3_000L, 7_000L, 15_000L)

    fun saveOrDelete(
        context: Context,
        phone: String,
        direction: String,
        sessionStartedAt: Long,
        text: String,
        companyId: String = "",
        authorProfileId: String = "",
        authorName: String = "",
    ): Boolean {
        val key = phoneKey(phone)
        if (key.isBlank()) return false
        val trimmed = text.trim()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (trimmed.isBlank()) {
            prefs.edit().remove(key).apply()
            return true
        }
        val now = System.currentTimeMillis()
        val session = CompanySessionStore.load(context.applicationContext)
        val resolvedAuthorId = authorProfileId.trim().ifBlank { session?.userId.orEmpty() }
        val resolvedAuthorName = authorName.trim().ifBlank { session?.userName.orEmpty() }
        val record = JSONObject().apply {
            put("phone", phone)
            put("direction", direction)
            put("session_started_at", sessionStartedAt.takeIf { it > 0L } ?: now)
            put("saved_at", now)
            put("note", trimmed)
            if (companyId.trim().isNotBlank()) put("company_id", companyId.trim())
            if (resolvedAuthorId.isNotBlank()) put("author_profile_id", resolvedAuthorId)
            if (resolvedAuthorName.isNotBlank()) put("author_name", resolvedAuthorName)
        }
        prefs.edit().putString(key, record.toString()).apply()
        return true
    }

    fun pendingForPhone(context: Context, phone: String): PendingCallNote? {
        val key = phoneKey(phone)
        if (key.isBlank()) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(key, "").orEmpty()
        if (raw.isBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val savedAt = json.optLong("saved_at", 0L)
        if (savedAt > 0L && System.currentTimeMillis() - savedAt > MAX_PENDING_AGE_MS) {
            prefs.edit().remove(key).apply()
            return null
        }
        val note = json.optString("note").trim()
        if (note.isBlank()) return null
        return PendingCallNote(
            phone = json.optString("phone").ifBlank { phone },
            direction = json.optString("direction"),
            sessionStartedAt = json.optLong("session_started_at", 0L),
            savedAt = savedAt,
            note = note,
            companyId = json.optString("company_id").trim(),
            authorProfileId = json.optString("author_profile_id").trim(),
            authorName = json.optString("author_name").trim(),
        )
    }

    fun activeSessionForPhone(context: Context, phone: String): PendingCallNote? {
        val prefs = context.applicationContext.getSharedPreferences(CALL_LIFECYCLE_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        val activeNumber = prefs.getString(KEY_NUMBER, "").orEmpty()
        if (!samePhone(activeNumber, phone)) return null
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        if (startedAt <= 0L) return null
        return PendingCallNote(
            phone = activeNumber.ifBlank { phone },
            direction = prefs.getString(KEY_DIRECTION, "").orEmpty(),
            sessionStartedAt = startedAt,
            savedAt = System.currentTimeMillis(),
            note = "",
        )
    }

    fun reconcileSoon(context: Context, phone: String) {
        val appContext = context.applicationContext
        thread(name = "callreport-pending-note-reconcile", isDaemon = true) {
            for (delay in RETRY_DELAYS_MS) {
                if (delay > 0L) Thread.sleep(delay)
                if (reconcilePendingForPhone(appContext, phone)) return@thread
            }
        }
    }

    fun reconcilePendingForPhone(context: Context, phone: String): Boolean {
        val pending = pendingForPhone(context, phone) ?: return false
        val call = findMatchingCall(context, pending) ?: return false
        val result = if (pending.companyId.isNotBlank()) {
            CallNoteTopicWriter.writeCallOrGeneral(
                context = context,
                phone = pending.phone,
                text = pending.note,
                direction = call.direction.ifBlank { pending.direction },
                callAt = call.startedAt,
                durationSeconds = call.durationSeconds,
                actionIssuedAt = 0L,
                companyId = pending.companyId,
                authorProfileId = pending.authorProfileId,
                authorName = pending.authorName,
            )
        } else {
            CallNoteWriter.writeCallOrGeneral(
                context = context,
                phone = pending.phone,
                text = pending.note,
                direction = call.direction.ifBlank { pending.direction },
                callAt = call.startedAt,
                durationSeconds = call.durationSeconds,
                actionIssuedAt = 0L,
            )
        }
        if (result.saved && !result.savedAsPending) {
            clear(context, pending.phone)
            return true
        }
        return false
    }

    /**
     * Once the user edits the real post-call row, the old pending draft must not
     * reconcile later and overwrite the newer text with the text typed mid-call.
     */
    fun clearResolvedForCall(context: Context, phone: String, direction: String, callAt: Long) {
        if (callAt <= 0L) return
        val pending = pendingForPhone(context, phone) ?: return
        val matched = findMatchingCall(context, pending)
        if (
            matched?.startedAt == callAt &&
            (direction.isBlank() || pending.direction.isBlank() || matched.direction.isBlank() || matched.direction == direction || pending.direction == direction)
        ) {
            clear(context, phone)
        }
    }

    private fun findMatchingCall(context: Context, pending: PendingCallNote): PhoneCallRecord? {
        val anchor = pending.sessionStartedAt.takeIf { it > 0L } ?: pending.savedAt
        val earliest = anchor - MATCH_BEFORE_SESSION_MS
        val latestAllowed = pending.savedAt.takeIf { it > 0L }?.plus(MATCH_AFTER_SAVE_MS) ?: Long.MAX_VALUE
        return PhoneCallReader.callsForPhone(context, pending.phone, limit = 20).firstOrNull { call ->
            call.startedAt > 0L &&
                call.startedAt >= earliest &&
                call.startedAt <= latestAllowed &&
                (pending.direction.isBlank() || call.direction.isBlank() || call.direction == pending.direction)
        }
    }

    private fun clear(context: Context, phone: String) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    private fun samePhone(left: String, right: String): Boolean {
        val a = phoneKey(left)
        val b = phoneKey(right)
        return a.isNotBlank() && b.isNotBlank() && (a == b || a.endsWith(b) || b.endsWith(a))
    }

    private fun phoneKey(value: String): String {
        val digits = value.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
