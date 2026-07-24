package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Produces the incoming-call popup progressively. One popup session is opened
 * immediately, then its three stable data rows are filled as each source becomes
 * available. Late results never create a second popup after dismissal.
 */
internal class IncomingCallLookupCoordinator(
    context: Context,
    private val config: AppConfig,
    private val phone: String,
    private val direction: String,
    private val fullscreen: Boolean,
    private val onLookupFinished: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val popupSession = IncomingCallPopupSessionStore.acquire(phone, direction)
    private val remoteAvailable = CallReportRemoteAccess.isReady(config)

    private var contactInfo: IncomingCallContactInfo? = null
    private var localRows: List<String>? = null
    private var remoteRows: List<PostCallLookupRemoteRow>? = null
    private var lookupResult: LookupResult? = null
    private var lookupFinished = false
    private var lookupSucceeded = false
    private var historyFinished = false
    private var historyFailed = false
    private var serverSlow = false
    private var finishedCallbackSent = false

    fun start() {
        // This is deliberately synchronous and cheap: the first overlay appears
        // before Contacts, Call Log or the network are touched.
        publishInitial()
        val policyQueued = submit(CONTACT_EXECUTOR, ::resolveContactAndStartLookups)
        if (!policyQueued) {
            synchronized(lock) {
                lookupFinished = true
                historyFinished = true
                historyFailed = remoteAvailable
            }
            publishCurrent()
            finishOnce()
            return
        }
        if (!submit(LOCAL_ROWS_EXECUTOR, ::loadLocalRows)) {
            synchronized(lock) { localRows = emptyList() }
            publishCurrent()
        }
    }

    private fun resolveContactAndStartLookups() {
        val resolvedContact = ContactGroupFilter.resolveIncomingCallContact(appContext, phone, config)
        synchronized(lock) { contactInfo = resolvedContact }
        if (!resolvedContact.shouldNotify) {
            IncomingCallPopupSessionStore.dismiss(popupSession.id)
            finishOnce()
            return
        }

        CallReportRuntime.ensureNotificationChannel(appContext)
        publishCurrent()
        if (!remoteAvailable) {
            synchronized(lock) {
                lookupFinished = true
                historyFinished = true
            }
            publishCurrent()
            finishOnce()
            return
        }

        val lookupQueued = submit(LOOKUP_EXECUTOR, ::loadLookup)
        if (!lookupQueued) {
            synchronized(lock) {
                lookupResult = fallbackLookup(resolvedContact)
                lookupFinished = true
                historyFinished = true
                historyFailed = true
            }
            publishCurrent()
            finishOnce()
            return
        }

        // A slow lookup releases BroadcastReceiver work at the deadline. A late
        // result may still fill the existing server row, but never reopen it.
        scheduleLookupFallback(resolvedContact)
    }

    private fun loadLocalRows() {
        val rows = runCatching {
            LocalCallStatsProvider.buildPopupInfoRows(appContext, phone)
        }.getOrDefault(emptyList())
        IncomingLookupPopupRowsCache.putLocalRows(phone, rows)
        synchronized(lock) { localRows = rows }
        publishCurrent()
    }

    private fun loadLookup() {
        val contact = synchronized(lock) { contactInfo } ?: return
        val fallback = fallbackLookup(contact)
        val attempt = runCatching {
            CallReportRuntime.fetchLookup(
                config = config,
                phone = phone,
                direction = direction,
                context = CallReportLookupContext(
                    communicationType = "phone",
                    contactName = contact.displayName.orEmpty(),
                ),
            )
        }
        val result = attempt.getOrElse { fallback }
        synchronized(lock) {
            lookupResult = result
            lookupFinished = true
            lookupSucceeded = attempt.isSuccess
            serverSlow = false
            if (attempt.isFailure) {
                historyFinished = true
                historyFailed = true
            }
        }
        publishCurrent()

        // Structured server history provides the actual server-notes row. It has
        // its own queue so it never delays contact resolution or local Call Log.
        if (attempt.isSuccess) {
            val historyQueued = submit(HISTORY_EXECUTOR, ::loadHistoryRows)
            if (!historyQueued) {
                synchronized(lock) {
                    historyFinished = true
                    historyFailed = true
                }
                publishCurrent()
            }
        }

        finishOnce()
    }

    private fun loadHistoryRows() {
        val attempt = runCatching {
            PostCallLookupRemoteRows.fromHistory(
                history = CallReportHistoryLookupClient.lookup(config, phone, limit = POPUP_HISTORY_LIMIT),
                phone = phone,
            )
        }
        val rows = attempt.getOrDefault(emptyList())
        IncomingLookupPopupRowsCache.putRemoteRows(phone, rows)
        synchronized(lock) {
            remoteRows = rows
            historyFinished = true
            historyFailed = attempt.isFailure
        }
        publishCurrent()
    }

    private fun scheduleLookupFallback(contact: IncomingCallContactInfo) {
        LOOKUP_TIMEOUT_EXECUTOR.schedule({
            val timedOut = synchronized(lock) {
                if (lookupFinished) {
                    false
                } else {
                    lookupResult = fallbackLookup(contact)
                    lookupFinished = true
                    serverSlow = true
                    true
                }
            }
            if (timedOut) {
                publishCurrent()
                finishOnce()
            }
        }, LOOKUP_DEADLINE_MS, TimeUnit.MILLISECONDS)
    }

    private fun publishInitial() {
        LookupPopupPresenter.show(
            context = appContext,
            result = LookupResult(
                title = phone,
                subtitle = phone,
                lines = emptyList(),
                openFormUrl = "",
            ),
            fullscreen = fullscreen,
            phone = phone,
            direction = direction,
            remoteRowsArePreloaded = true,
            popupSessionId = popupSession.id,
            progressiveRows = IncomingCallPopupProgress.loading(remoteAvailable),
            updateOnly = !popupSession.isNew,
        )
    }

    private fun publishCurrent() {
        val snapshot = synchronized(lock) {
            val contact = contactInfo
            if (contact?.shouldNotify == false) return
            Snapshot(
                contact = contact,
                lookup = lookupResult,
                progress = progressLocked(),
            )
        }
        val fallback = fallbackLookup(snapshot.contact)
        val remote = snapshot.lookup
        val result = if (remote == null) {
            fallback
        } else {
            remote.copy(
                title = snapshot.contact?.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: remote.title.ifBlank { phone },
            )
        }
        LookupPopupPresenter.show(
            context = appContext,
            result = result,
            fullscreen = fullscreen,
            phone = phone,
            direction = direction,
            remoteRowsArePreloaded = true,
            popupSessionId = popupSession.id,
            progressiveRows = snapshot.progress,
            updateOnly = true,
        )
    }

    private fun progressLocked(): IncomingCallPopupProgress {
        val loadedLocalRows = localRows
        val callLine = when (loadedLocalRows) {
            null -> IncomingCallPopupProgress.LOADING
            else -> loadedLocalRows
                .firstOrNull { !isLocalNoteRow(it) }
                .orEmpty()
                .ifBlank { "Няма предишни разговори" }
        }
        val localNoteLine = when (loadedLocalRows) {
            null -> IncomingCallPopupProgress.LOADING
            else -> loadedLocalRows
                .asSequence()
                .filter(::isLocalNoteRow)
                .map(::stripLocalNoteIcon)
                .filter { it.isNotBlank() }
                .take(MAX_LOCAL_NOTES_IN_ROW)
                .joinToString(" • ")
                .ifBlank { "Няма локални бележки" }
        }
        val serverNoteLine = when {
            !remoteAvailable -> "Сървърът не е настроен"
            remoteRows?.isNotEmpty() == true -> remoteRows.orEmpty()
                .asSequence()
                .map(::formatRemoteRow)
                .filter { it.isNotBlank() }
                .take(MAX_SERVER_NOTES_IN_ROW)
                .joinToString(" • ")
            historyFinished && historyFailed -> "Сървърът не отговори"
            historyFinished -> "Няма сървърни бележки"
            serverSlow -> "Сървърът отговаря бавно…"
            lookupFinished && !lookupSucceeded -> "Сървърът не отговори"
            else -> IncomingCallPopupProgress.LOADING
        }
        return IncomingCallPopupProgress(
            calls = callLine,
            localNotes = localNoteLine,
            serverNotes = serverNoteLine,
        )
    }

    private fun fallbackLookup(contact: IncomingCallContactInfo? = null): LookupResult = LookupResult(
        title = contact?.displayName?.takeIf { it.isNotBlank() } ?: phone,
        subtitle = phone,
        lines = emptyList(),
        openFormUrl = "",
    )

    private fun submit(executor: ThreadPoolExecutor, block: () -> Unit): Boolean {
        return try {
            executor.execute(block)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun finishOnce() {
        val shouldFinish = synchronized(lock) {
            if (finishedCallbackSent) false else {
                finishedCallbackSent = true
                true
            }
        }
        if (shouldFinish) onLookupFinished()
    }

    private fun isLocalNoteRow(value: String): Boolean =
        value.startsWith(ICON_GENERAL_NOTE) || value.startsWith(ICON_CALL_NOTE)

    private fun stripLocalNoteIcon(value: String): String = value
        .removePrefix(ICON_GENERAL_NOTE)
        .removePrefix(ICON_CALL_NOTE)
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun formatRemoteRow(row: PostCallLookupRemoteRow): String =
        listOf(row.companyName.ifBlank { "Сървър" }, row.note.trim())
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    private data class Snapshot(
        val contact: IncomingCallContactInfo?,
        val lookup: LookupResult?,
        val progress: IncomingCallPopupProgress,
    )

    private companion object {
        private const val CONTACT_QUEUE_SIZE = 8
        private const val LOCAL_ROWS_QUEUE_SIZE = 8
        private const val LOOKUP_QUEUE_SIZE = 12
        private const val HISTORY_QUEUE_SIZE = 12
        private const val LOOKUP_DEADLINE_MS = 4_500L
        private const val POPUP_HISTORY_LIMIT = 20
        private const val MAX_LOCAL_NOTES_IN_ROW = 2
        private const val MAX_SERVER_NOTES_IN_ROW = 3
        private const val ICON_GENERAL_NOTE = "☰"
        private const val ICON_CALL_NOTE = "💬"

        private val CONTACT_EXECUTOR = ThreadPoolExecutor(
            1,
            1,
            20L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(CONTACT_QUEUE_SIZE),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val LOCAL_ROWS_EXECUTOR = ThreadPoolExecutor(
            1,
            1,
            20L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(LOCAL_ROWS_QUEUE_SIZE),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val LOOKUP_EXECUTOR = ThreadPoolExecutor(
            2,
            2,
            20L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(LOOKUP_QUEUE_SIZE),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val HISTORY_EXECUTOR = ThreadPoolExecutor(
            1,
            1,
            20L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(HISTORY_QUEUE_SIZE),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val LOOKUP_TIMEOUT_EXECUTOR = ScheduledThreadPoolExecutor(1).apply {
            removeOnCancelPolicy = true
        }
    }
}

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
            val existing = entries.values
                .asSequence()
                .filter { it.key == key && !it.dismissed && now - it.createdAtMs <= REUSE_WINDOW_MS }
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
