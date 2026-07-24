package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Refreshes Home company-note and phase labels without delaying the call-log rows. */
internal class HomeCompanyGeneralNotesController(
    private val context: Context,
    private val handler: Handler,
    private val onChanged: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    private val busyTokens = linkedSetOf<Long>()
    private var requestSignature = ""
    private val initialSnapshot = HomeCompanyScopeSnapshotCache.read(context.applicationContext)
    private var labelsByPhoneKey: Map<String, List<HomeCompanyScopeLabel>> = initialSnapshot.labelsByPhoneKey
    private var serverBackedPhoneKeys: Set<String> = initialSnapshot.serverBackedPhoneKeys

    fun labelsFor(calls: List<PhoneCallRecord>): Map<String, List<HomeCompanyScopeLabel>> {
        val keys = calls.map { HomeCallPageLoader.noteKey(it.number) }.filter { it.isNotBlank() }.toSet()
        return labelsByPhoneKey.filterKeys { it in keys }
    }

    fun serverBackedPhoneKeysFor(calls: List<PhoneCallRecord>): Set<String> {
        val keys = calls.mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.number) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        return serverBackedPhoneKeys.filterTo(linkedSetOf()) { it in keys }
    }

    fun hasServerBackedPhone(phone: String): Boolean {
        val key = HomeCallPageLoader.noteKey(phone)
        return key.isNotBlank() && key in serverBackedPhoneKeys
    }

    fun refresh(calls: List<PhoneCallRecord>) {
        val config = ConfigStore.load(context.applicationContext)
        val phones = calls
            .map { it.number }
            .filter { HomeCallPageLoader.noteKey(it).isNotBlank() }
            .distinctBy { HomeCallPageLoader.noteKey(it) }
            .take(20)
        val nextSignature = listOf(
            config.remoteEnabled.toString(),
            config.baseUrl,
            config.accessToken,
            phones.joinToString("|") { HomeCallPageLoader.noteKey(it) },
        ).joinToString("#")
        if (nextSignature == requestSignature) return
        requestSignature = nextSignature

        if (!CallReportRemoteAccess.isReady(config) || phones.isEmpty()) {
            if (labelsByPhoneKey.isNotEmpty() || serverBackedPhoneKeys.isNotEmpty()) {
                labelsByPhoneKey = emptyMap()
                serverBackedPhoneKeys = emptySet()
                onChanged()
            }
            return
        }

        val busyToken = (context as? Activity)?.let {
            HomeBusyTooltipUi.begin(it, HomeBusyWork.COMPANY_DATA)
        } ?: 0L
        if (busyToken > 0L) busyTokens += busyToken
        val currentGeneration = generation.incrementAndGet()
        executor.execute {
            val fresh = runCatching {
                HomeCompanyGeneralNoteLabels.fetch(context.applicationContext, config, phones)
            }.getOrDefault(HomeCompanyScopeSnapshot())
            val merged = HomeCompanyScopeSnapshotCache.mergeAndStore(
                context = context.applicationContext,
                requestedPhones = phones,
                fresh = fresh,
            )
            handler.post {
                finishBusy(busyToken)
                if (currentGeneration != generation.get()) return@post
                if (
                    labelsByPhoneKey == merged.labelsByPhoneKey &&
                    serverBackedPhoneKeys == merged.serverBackedPhoneKeys
                ) return@post
                labelsByPhoneKey = merged.labelsByPhoneKey
                serverBackedPhoneKeys = merged.serverBackedPhoneKeys
                onChanged()
            }
        }
    }

    fun invalidate() {
        requestSignature = ""
        generation.incrementAndGet()
        finishAllBusy()
        val cached = HomeCompanyScopeSnapshotCache.read(context.applicationContext)
        labelsByPhoneKey = cached.labelsByPhoneKey
        serverBackedPhoneKeys = cached.serverBackedPhoneKeys
    }

    fun release() {
        generation.incrementAndGet()
        finishAllBusy()
        executor.shutdownNow()
    }

    private fun finishBusy(token: Long) {
        if (token <= 0L) return
        busyTokens.remove(token)
        (context as? Activity)?.let { HomeBusyTooltipUi.end(it, token) }
    }

    private fun finishAllBusy() {
        busyTokens.toList().forEach(::finishBusy)
    }
}