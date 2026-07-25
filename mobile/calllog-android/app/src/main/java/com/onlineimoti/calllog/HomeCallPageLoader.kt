package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.Future

object HomeCallPageLoader {
    private const val SEARCH_SCAN_LIMIT = 500
    private const val FILTERED_CALL_SCAN_LIMIT = 500
    private const val FILTERED_SMS_SCAN_LIMIT = 150
    private const val SEARCH_CALL_CACHE_MS = 10_000L
    private const val MAX_FILTERED_SEARCH_CALL_CACHES = 8

    private val searchCallCacheLock = Any()
    private var cachedUnfilteredSearchCalls = TimedCalls(0L, emptyList())
    private val cachedFilteredSearchCalls = linkedMapOf<String, TimedCalls>()
    private val searchSourceExecutor = Executors.newFixedThreadPool(3)

    fun calls(
        context: Context,
        activePhoneFilter: String,
        searchQuery: String,
        pageIndex: Int,
        pageSize: Int,
        crmMode: Boolean = false,
    ): List<PhoneCallRecord> {
        val normalizedSearch = searchQuery.trim()
        if (normalizedSearch.isNotBlank()) {
            return searchCalls(context, activePhoneFilter, normalizedSearch, pageIndex, pageSize, crmMode)
        }
        return when {
            activePhoneFilter.isNotBlank() -> filteredTimelineForPhone(
                context,
                activePhoneFilter,
                pageIndex,
                pageSize,
                crmMode,
            )
            crmMode -> crmCalls(context, pageIndex, pageSize)
            else -> PhoneCallReader.recentCalls(context, limit = pageSize, offset = pageIndex * pageSize)
        }
    }

    fun isSearchTooShort(query: String): Boolean {
        val trimmed = query.trim()
        val digits = trimmed.filter { it.isDigit() }
        return trimmed.isNotBlank() && trimmed.length < 2 && digits.length < 3
    }

    /** Clears only local, short-lived snapshots; it never alters stored notes or contacts. */
    fun clearSearchCache() {
        synchronized(searchCallCacheLock) {
            cachedUnfilteredSearchCalls = TimedCalls(0L, emptyList())
            cachedFilteredSearchCalls.clear()
        }
        ContactSearchProvider.invalidate()
        StoredNoteSearchProvider.invalidate()
    }

    /** A CRM row is either an unknown number or a number explicitly marked CRM. */
    fun isCrmEligible(context: Context, phone: String): Boolean =
        HomeCrmEligibility.isEligible(context, phone)

    /** Resolves the CRM category for each phone in one Contacts pass for Home-only filtering. */
    internal fun crmContactKinds(
        context: Context,
        phones: Iterable<String>,
    ): Map<String, HomeCrmContactKind> = HomeCrmEligibility.contactKinds(context, phones)

    fun crmEligiblePhoneKeys(context: Context, phones: Iterable<String>): Set<String> =
        HomeCrmEligibility.eligiblePhoneKeys(context, phones)

    /** Raw chronological CRM candidates before contact, direction or company filters are applied. */
    fun crmCandidateCalls(context: Context): List<PhoneCallRecord> =
        HomeCrmEligibility.candidateCalls(context)

    private fun crmCalls(context: Context, pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        return crmCandidateCalls(context).page(pageIndex, pageSize)
    }

    private fun filteredTimelineForPhone(
        context: Context,
        phone: String,
        pageIndex: Int,
        pageSize: Int,
        crmMode: Boolean,
    ): List<PhoneCallRecord> {
        val calls = PhoneCallReader.callsForPhone(
            context = context,
            phone = phone,
            limit = FILTERED_CALL_SCAN_LIMIT,
            offset = 0,
        )
        val messages = SmsMessageReader.messagesForPhone(
            context = context,
            phone = phone,
            limit = FILTERED_SMS_SCAN_LIMIT,
        ).map { sms ->
            PhoneCallRecord(
                number = phone,
                name = "",
                direction = if (sms.isOutgoing) "sms_out" else "sms_in",
                startedAt = sms.timestampMs,
                durationSeconds = 0L,
                smsBody = sms.body,
                providerId = sms.providerId,
            )
        }
        val timeline = (calls + messages).sortedByDescending { item -> item.startedAt }
        return (if (crmMode) HomeCrmEligibility.filter(context, timeline) else timeline)
            .page(pageIndex, pageSize)
    }

    private fun searchCalls(
        context: Context,
        activePhoneFilter: String,
        query: String,
        pageIndex: Int,
        pageSize: Int,
        crmMode: Boolean,
    ): List<PhoneCallRecord> {
        if (isSearchTooShort(query)) return emptyList()
        val sources = loadSearchSources(context.applicationContext, activePhoneFilter, query)
        val recentCalls = sources.calls
        val contactResults = sources.contacts
            .filter { result ->
                activePhoneFilter.isBlank() || noteKey(result.phone) == noteKey(activePhoneFilter)
            }
        val noteResults = sources.notes
            .filter { result ->
                activePhoneFilter.isBlank() || result.phoneKey == noteKey(activePhoneFilter)
            }

        val seen = linkedSetOf<String>()
        val ordered = arrayListOf<PhoneCallRecord>()
        contactResults.forEach { contact ->
            val key = noteKey(contact.phone)
            if (!seen.add("contact:$key")) return@forEach
            ordered.add(bestCallForPhone(recentCalls, contact.phone, contact.name, 0L, ""))
        }
        noteResults.forEach { note ->
            val resultKey = if (note.isCallNote && note.callAt > 0L) {
                "call:${note.phoneKey}:${note.callAt}:${note.direction}"
            } else {
                "note:${note.phoneKey}"
            }
            if (!seen.add(resultKey)) return@forEach
            ordered.add(bestCallForPhone(recentCalls, note.phone, note.phone, note.callAt, note.direction))
        }
        val filtered = if (crmMode) HomeCrmEligibility.filter(context, ordered) else ordered
        // Search may collect contacts and notes in relevance order. Home is a
        // timeline, so establish the same newest-first order before pagination.
        return filtered.sortedByDescending { item -> item.startedAt }.page(pageIndex, pageSize)
    }

    /** The three data sources are independent; run them concurrently without reducing scope. */
    private fun loadSearchSources(
        context: Context,
        activePhoneFilter: String,
        query: String,
    ): SearchSources {
        val callsFuture = searchSourceExecutor.submit<List<PhoneCallRecord>> {
            searchCallSnapshot(context, activePhoneFilter)
        }
        val contactsFuture = searchSourceExecutor.submit<List<ContactSearchResult>> {
            ContactSearchProvider.search(context, query)
        }
        val notesFuture = searchSourceExecutor.submit<List<StoredNoteSearchResult>> {
            StoredNoteSearchProvider.search(context, query)
        }
        return SearchSources(
            calls = await(callsFuture, emptyList()),
            contacts = await(contactsFuture, emptyList()),
            notes = await(notesFuture, emptyList()),
        )
    }

    private fun searchCallSnapshot(context: Context, activePhoneFilter: String): List<PhoneCallRecord> {
        val key = noteKey(activePhoneFilter)
        val now = System.currentTimeMillis()
        synchronized(searchCallCacheLock) {
            val cached = if (key.isBlank()) {
                cachedUnfilteredSearchCalls
            } else {
                cachedFilteredSearchCalls[key]
            }
            if (cached != null && now - cached.loadedAtMs < SEARCH_CALL_CACHE_MS) return cached.calls
        }
        val loaded = if (activePhoneFilter.isBlank()) {
            PhoneCallReader.recentCalls(context, limit = SEARCH_SCAN_LIMIT, offset = 0)
        } else {
            PhoneCallReader.callsForPhone(
                context,
                activePhoneFilter,
                limit = SEARCH_SCAN_LIMIT,
                offset = 0,
            )
        }
        synchronized(searchCallCacheLock) {
            val entry = TimedCalls(now, loaded)
            if (key.isBlank()) {
                cachedUnfilteredSearchCalls = entry
            } else {
                cachedFilteredSearchCalls[key] = entry
                while (cachedFilteredSearchCalls.size > MAX_FILTERED_SEARCH_CALL_CACHES) {
                    cachedFilteredSearchCalls.remove(cachedFilteredSearchCalls.entries.first().key)
                }
            }
        }
        return loaded
    }

    private fun <T> await(future: Future<T>, fallback: T): T {
        return try {
            future.get()
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun bestCallForPhone(
        calls: List<PhoneCallRecord>,
        phone: String,
        fallbackName: String,
        callAt: Long,
        direction: String,
    ): PhoneCallRecord {
        val key = noteKey(phone)
        val exact = if (callAt > 0L) {
            calls.firstOrNull { call ->
                noteKey(call.number) == key &&
                    call.startedAt == callAt &&
                    (direction.isBlank() || call.direction == direction)
            }
        } else {
            null
        }
        val latest = calls.firstOrNull { call -> noteKey(call.number) == key }
        return exact ?: latest ?: PhoneCallRecord(
            number = phone,
            name = fallbackName.takeIf { it.isNotBlank() && noteKey(it) != key }.orEmpty(),
            direction = direction,
            startedAt = callAt,
            durationSeconds = 0L,
        )
    }

    private fun List<PhoneCallRecord>.page(pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        return drop(pageIndex * pageSize).take(pageSize)
    }

    /**
     * Returns one entry for every visible phone. A blank value is an intentional
     * tombstone telling staged Home renders and the snapshot cache that a previously
     * visible local yellow note has been deleted.
     */
    fun contactNotes(context: Context, calls: List<PhoneCallRecord>): Map<String, String> {
        val notes = linkedMapOf<String, String>()
        calls.map { call -> call.number }
            .distinctBy { number -> noteKey(number) }
            .forEach { number ->
                val key = noteKey(number)
                if (key.isNotBlank()) notes[key] = ContactNoteReader.generalNoteForPhone(context, number)
            }
        return notes
    }

    fun contactNames(context: Context, calls: List<PhoneCallRecord>): Map<String, String> {
        val names = linkedMapOf<String, String>()
        calls.map { call -> call.number }
            .distinctBy { number -> noteKey(number) }
            .forEach { number ->
                ContactGroupFilter.resolveDisplayName(context, number)
                    .orEmpty()
                    .takeIf { name -> name.isNotBlank() }
                    ?.let { name -> names[noteKey(number)] = name }
            }
        return names
    }

    fun noteKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private data class TimedCalls(
        val loadedAtMs: Long,
        val calls: List<PhoneCallRecord>,
    )

    private data class SearchSources(
        val calls: List<PhoneCallRecord>,
        val contacts: List<ContactSearchResult>,
        val notes: List<StoredNoteSearchResult>,
    )
}
