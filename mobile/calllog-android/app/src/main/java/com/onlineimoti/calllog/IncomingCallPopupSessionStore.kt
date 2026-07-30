package com.onlineimoti.calllog

import java.util.concurrent.atomic.AtomicLong

/** Three permanent information rows in the incoming-call popup. */
internal data class IncomingCallPopupProgress(
    val calls: String,
    val localNotes: String,
    val serverNotes: String,
) {
    companion object {
        const val LOADING = "… loading"

        fun loading(remoteAvailable: Boolean) = IncomingCallPopupProgress(
            calls = LOADING,
            localNotes = LOADING,
            serverNotes = if (remoteAvailable) LOADING else "Сървърът не е настроен",
        )
    }
}

/**
 * Process-local protection against two Android call sources opening two cards,
 * and against late asynchronous results reopening a card the user dismissed.
 */
internal object IncomingCallPopupSessionStore {
    internal data class Lease(val id: String, val isNew: Boolean)

    private data class Entry(
        val id: String,
        val key: String,
        val createdAtMs: Long,
        val dismissed: Boolean,
    )

    private const val REUSE_WINDOW_MS = 5_000L
    private const val ENTRY_TTL_MS = 120_000L
    private val sequence = AtomicLong(0L)
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    fun acquire(phone: String, direction: String): Lease {
        val now = System.currentTimeMillis()
        val key = "${phoneKey(phone)}|${direction.trim().lowercase()}"
        synchronized(lock) {
            pruneLocked(now)
            // Reuse even a dismissed session for duplicate Android sources. That
            // makes the second event a no-op instead of reopening the card.
            val existing = entries.values
                .asSequence()
                .filter { it.key == key && now - it.createdAtMs <= REUSE_WINDOW_MS }
                .maxByOrNull { it.createdAtMs }
            if (existing != null) return Lease(existing.id, isNew = false)

            val id = "$key|$now|${sequence.incrementAndGet()}"
            entries[id] = Entry(id = id, key = key, createdAtMs = now, dismissed = false)
            return Lease(id, isNew = true)
        }
    }

    fun dismiss(sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            val current = entries[sessionId] ?: return
            entries[sessionId] = current.copy(dismissed = true)
        }
    }

    fun isDismissed(sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        synchronized(lock) {
            pruneLocked(System.currentTimeMillis())
            return entries[sessionId]?.dismissed == true
        }
    }

    private fun pruneLocked(now: Long) {
        entries.entries.removeAll { (_, entry) -> now - entry.createdAtMs > ENTRY_TTL_MS }
    }

    private fun phoneKey(phone: String): String {
        val digits = phone.filter(Char::isDigit)
        return if (digits.length > 9) digits.takeLast(9) else digits.ifBlank { phone.trim() }
    }
}
