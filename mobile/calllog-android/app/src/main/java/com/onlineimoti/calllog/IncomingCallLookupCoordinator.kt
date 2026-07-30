package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
        val policyQueued = submit(IncomingCallLookupExecutors.contact, ::resolveContactAndStartLookups)
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
        if (!submit(IncomingCallLookupExecutors.localRows, ::loadLocalRows)) {
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

        val lookupQueued = submit(IncomingCallLookupExecutors.lookup, ::loadLookup)
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
            val historyQueued = submit(IncomingCallLookupExecutors.history, ::loadHistoryRows)
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
                history = CallReportHistoryLookupClient.lookup(config, phone, limit = IncomingCallLookupExecutors.POPUP_HISTORY_LIMIT),
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
        IncomingCallLookupExecutors.timeout.schedule({
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
        }, IncomingCallLookupExecutors.LOOKUP_DEADLINE_MS, TimeUnit.MILLISECONDS)
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

    private fun progressLocked(): IncomingCallPopupProgress =
        IncomingCallPopupProgressFormatter.build(
            remoteAvailable = remoteAvailable,
            localRows = localRows,
            remoteRows = remoteRows,
            historyFinished = historyFinished,
            historyFailed = historyFailed,
            serverSlow = serverSlow,
            lookupFinished = lookupFinished,
            lookupSucceeded = lookupSucceeded,
        )

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

    private data class Snapshot(
        val contact: IncomingCallContactInfo?,
        val lookup: LookupResult?,
        val progress: IncomingCallPopupProgress,
    )


}
