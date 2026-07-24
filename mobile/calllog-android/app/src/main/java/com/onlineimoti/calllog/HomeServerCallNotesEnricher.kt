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

    fun invalidate() {
        generation.incrementAndGet()
        cachedHistory = null
        finishAllBusy()
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
        val config = ConfigStore.load(appContext)
        if (!CallReportRemoteAccess.isReady(config)) {
            handler.post(onFinished)
            return
        }
        val expectedGeneration = generation.get()
        val phones = renderData.calls
            .filterNot { it.isSms }
            .map { it.number }
            .distinctBy(HomeCallPageLoader::noteKey)
        if (phones.isEmpty()) {
            handler.post(onFinished)
            return
        }

        val busyToken = activity?.let {
            HomeBusyTooltipUi.begin(it, HomeBusyWork.SERVER_NOTES)
        } ?: 0L
        if (busyToken > 0L) busyTokens += busyToken
        executor.execute {
            val history = runCatching {
                historyForPage(config, phones)
            }.getOrDefault(CallReportHistoryLookupResult())
            // Pending operations come last. Their newer timestamp wins immediately;
            // an empty pending note acts as a tombstone until the server confirms it.
            val combinedEvents = history.events + CompanyCallNoteOutbox.pendingEvents(appContext, phones)
            val updated = renderData.copy(
                contactNotesByNumber = mergeServerGeneralNotes(
                    calls = renderData.calls,
                    existing = renderData.contactNotesByNumber,
                    serverEvents = history.events,
                ),
                callNotesByCall = HomeCallNotesResolver.mergeWithServer(
                    calls = renderData.calls,
                    localNotes = renderData.callNotesByCall,
                    serverEvents = combinedEvents,
                    principal = history.principal,
                ),
            )
            handler.post {
                finishBusy(busyToken)
                if (expectedGeneration != generation.get()) return@post
                if (updated != renderData) onUpdated(updated)
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

    /**
     * Home first requests server notes for the immediately visible rows, then asks
     * again after local notes arrive. Reuse the same fresh response for that second
     * merge instead of issuing an identical HTTP request.
     */
    private fun historyForPage(
        config: AppConfig,
        phones: List<String>,
    ): CallReportHistoryLookupResult {
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

        val loaded = CallReportHistoryLookupClient.lookupMany(config, phones, appContext)
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

        val merged = existing.toMutableMap()
        requestedKeys.forEach { key ->
            if (ServerNoteVisuals.isPrefixed(merged[key].orEmpty())) merged.remove(key)
        }
        latest.forEach { (key, value) ->
            if (merged[key].isNullOrBlank()) merged[key] = value.second
        }
        return merged
    }

    private data class CachedHistory(
        val signature: String,
        val loadedAtMs: Long,
        val result: CallReportHistoryLookupResult,
    )

    private companion object {
        const val PAGE_HISTORY_CACHE_MS = 5_000L
    }
}
