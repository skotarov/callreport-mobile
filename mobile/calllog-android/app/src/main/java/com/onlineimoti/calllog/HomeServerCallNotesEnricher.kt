package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Adds matching server and pending company notes to one rendered Home page. */
internal class HomeServerCallNotesController(
    context: Context,
    private val handler: Handler,
) {
    private val activity = context as? Activity
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    private val busyTokens = linkedSetOf<Long>()
    @Volatile private var cachedHistory: CachedHistory? = null
    @Volatile private var observedNoteRevision = HomeNoteChangeSignal.current(appContext)

    /** Cancels obsolete callbacks while retaining a reusable response for the same page. */
    fun cancelPending() {
        generation.incrementAndGet()
        finishAllBusy()
    }

    /** Real note/settings changes must also force the next request to read the server again. */
    fun invalidate() {
        cancelPending()
        cachedHistory = null
        observedNoteRevision = HomeNoteChangeSignal.current(appContext)
    }

    fun enrichAsync(
        renderData: HomeRenderData,
        onFinished: () -> Unit = {},
        onUpdated: (HomeRenderData) -> Unit,
    ) {
        if (renderData.calls.isEmpty()) {
            handler.post(onFinished)
            return
        }

        // Home may have stayed alive while an editor on top saved a note. The normal
        // renderer can therefore re-enter with the same page signature while the
        // 30-second history response is still cached. A durable note revision makes
        // that cache invalid immediately, so edited blue call notes are fetched again.
        val currentNoteRevision = HomeNoteChangeSignal.current(appContext)
        if (currentNoteRevision != observedNoteRevision) {
            cachedHistory = null
            observedNoteRevision = currentNoteRevision
        }

        // Capture this before reading the snapshot. If a deletion happens after this
        // point, the final cache write is rejected atomically by storeIfRevision().
        val expectedMutationRevision = HomeNotesSnapshotCache.mutationRevision(appContext)

        // This method is entered from Home's main-thread render callback. Apply the
        // durable last-known notes synchronously, before Android draws the first frame,
        // so calls, yellow notes and blue notes appear together instead of in stages.
        val cachedData = HomeNotesSnapshotCache.mergeCached(appContext, renderData)
        if (cachedData != renderData) onUpdated(cachedData)

        val expectedGeneration = generation.get()
        val config = ConfigStore.load(appContext)
        if (!CallReportRemoteAccess.isReady(config)) {
            persistWithoutRemote(
                data = cachedData,
                expectedGeneration = expectedGeneration,
                expectedMutationRevision = expectedMutationRevision,
                onFinished = onFinished,
            )
            return
        }
        val phones = cachedData.calls
            .filterNot { it.isSms }
            .map { it.number }
            .distinctBy(HomeCallPageLoader::noteKey)
        if (phones.isEmpty()) {
            persistWithoutRemote(
                data = cachedData,
                expectedGeneration = expectedGeneration,
                expectedMutationRevision = expectedMutationRevision,
                onFinished = onFinished,
            )
            return
        }

        val busyToken = activity?.let {
            HomeBusyTooltipUi.begin(it, HomeBusyWork.SERVER_NOTES)
        } ?: 0L
        if (busyToken > 0L) busyTokens += busyToken
        executor.execute {
            val history = runCatching { historyForPage(config, phones) }.getOrNull()
            if (history == null) {
                storeIfCurrent(cachedData, expectedGeneration, expectedMutationRevision)
                handler.post {
                    finishBusy(busyToken)
                    if (expectedGeneration == generation.get()) onFinished()
                }
                return@execute
            }
            // Pending operations come last. Their newer timestamp wins immediately;
            // an empty pending note acts as a tombstone until the server confirms it.
            val combinedEvents = history.events +
                CompanyCallNoteOutbox.pendingEvents(appContext, phones) +
                CallReportNoteOutbox.pendingExistingServerEvents(appContext, phones)
            val updated = cachedData.copy(
                contactNotesByNumber = mergeServerGeneralNotes(
                    calls = cachedData.calls,
                    existing = cachedData.contactNotesByNumber,
                    serverEvents = history.events,
                ),
                callNotesByCall = HomeCallNotesResolver.mergeWithServer(
                    calls = cachedData.calls,
                    localNotes = cachedData.callNotesByCall,
                    serverEvents = combinedEvents,
                    principal = history.principal,
                ),
            )
            if (!storeIfCurrent(updated, expectedGeneration, expectedMutationRevision)) {
                handler.post { finishBusy(busyToken) }
                return@execute
            }
            handler.post {
                finishBusy(busyToken)
                if (expectedGeneration != generation.get()) return@post
                if (updated != cachedData) onUpdated(updated)
                onFinished()
            }
        }
    }

    fun release() {
        generation.incrementAndGet()
        cachedHistory = null
        finishAllBusy()
        executor.shutdownNow()
    }

    private fun persistWithoutRemote(
        data: HomeRenderData,
        expectedGeneration: Int,
        expectedMutationRevision: Long,
        onFinished: () -> Unit,
    ) {
        executor.execute {
            storeIfCurrent(data, expectedGeneration, expectedMutationRevision)
            handler.post {
                if (expectedGeneration == generation.get()) onFinished()
            }
        }
    }

    /** Never let a response started before a note mutation repopulate the snapshot. */
    private fun storeIfCurrent(
        data: HomeRenderData,
        expectedGeneration: Int,
        expectedMutationRevision: Long,
    ): Boolean {
        if (expectedGeneration != generation.get()) return false
        if (!HomeNotesSnapshotCache.storeIfRevision(appContext, data, expectedMutationRevision)) return false
        return expectedGeneration == generation.get()
    }

    /**
     * Home first requests server notes for the immediately visible rows, then asks
     * again after local notes arrive. Reuse the same fresh response for that second
     * merge instead of issuing an identical HTTP request.
     */
    private fun historyForPage(
        config: AppConfig,
        phones: List<String>,
    ): CallReportHistoryLookupResult? {
        val signature = buildString {
            append(config.baseUrl.trim())
            append('#')
            append(config.accessToken.trim())
            append('#')
            phones.map(HomeCallPageLoader::noteKey)
                .filter { it.isNotBlank() }
                .sorted()
                .forEach {
                    append(it)
                    append('|')
                }
        }
        val now = System.currentTimeMillis()
        cachedHistory?.takeIf {
            it.signature == signature && now - it.loadedAtMs < PAGE_HISTORY_CACHE_MS
        }?.let { return it.result }

        val loaded = CallReportHistoryLookupClient.lookupManyOrNull(config, phones, appContext) ?: return null
        cachedHistory = CachedHistory(signature, now, loaded)
        return loaded
    }

    private fun finishBusy(token: Long) {
        if (token <= 0L) return
        busyTokens.remove(token)
        activity?.let { HomeBusyTooltipUi.end(it, token) }
    }

    private fun finishAllBusy() {
        busyTokens.toList().forEach(::finishBusy)
    }

    private fun mergeServerGeneralNotes(
        calls: List<PhoneCallRecord>,
        existing: Map<String, String>,
        serverEvents: List<CallReportHistoryEvent>,
    ): Map<String, String> {
        val requestedKeys = calls
            .filterNot { it.isSms }
            .mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.number) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        if (requestedKeys.isEmpty()) return existing

        val latest = linkedMapOf<String, Pair<Long, String>>()
        serverEvents.forEach { event ->
            val explicitGeneral = CallReportServerNoteClassifier.isExplicitGeneralNote(event)
            if (!CompanyGeneralNoteCachePolicy.belongsInGenericLane(explicitGeneral, event.companyId)) return@forEach
            val key = HomeCallPageLoader.noteKey(event.phone)
            if (key.isBlank() || key !in requestedKeys) return@forEach
            val note = event.note.trim()
            if (note.isBlank()) return@forEach
            val changedAt = maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs)
            val current = latest[key]
            if (current == null || changedAt >= current.first) {
                latest[key] = changedAt to ServerNoteVisuals.prefixed(note)
            }
        }

        return existing.toMutableMap().apply {
            requestedKeys.forEach { key ->
                val combined = HomeGeneralNoteBundle.replaceServer(
                    existing = get(key),
                    serverValue = latest[key]?.second,
                )
                if (combined.isBlank()) remove(key) else put(key, combined)
            }
        }
    }

    private data class CachedHistory(
        val signature: String,
        val loadedAtMs: Long,
        val result: CallReportHistoryLookupResult,
    )

    private companion object {
        const val PAGE_HISTORY_CACHE_MS = 30_000L
    }
}
