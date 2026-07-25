package com.onlineimoti.calllog

internal enum class HomeRenderMergeMode {
    PROVISIONAL,
    SUPPLEMENTAL,
    /** Fresh local yellow notes replace the local lane, including deletions. */
    LOCAL_GENERAL_AUTHORITATIVE,
    AUTHORITATIVE,
}

internal data class HomeRenderState(
    val contactNotesByNumber: Map<String, String>,
    val contactNamesByNumber: Map<String, String>,
    val callNotesByCall: Map<String, HomeCallNote>,
)

/** Builds the effective visible state without letting incomplete stages erase good data. */
internal object HomeRenderStateMerger {
    private const val MAX_REMEMBERED_NAMES = 2_000

    fun merge(
        calls: List<PhoneCallRecord>,
        incoming: HomeRenderData,
        currentContactNotes: Map<String, String>,
        currentContactNames: Map<String, String>,
        currentCallNotes: Map<String, HomeCallNote>,
        rememberedNames: LinkedHashMap<String, String>,
        mode: HomeRenderMergeMode,
    ): HomeRenderState {
        val phoneKeys = calls.mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.number) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        val callKeys = calls.mapTo(linkedSetOf(), HomeCallNotesResolver::keyFor)

        val names = linkedMapOf<String, String>()
        phoneKeys.forEach { key ->
            rememberedNames[key]?.takeIf { it.isNotBlank() }?.let { names[key] = it }
            currentContactNames[key]?.trim()?.takeIf { it.isNotBlank() }?.let { names[key] = it }
        }
        incoming.contactNamesByNumber.forEach { (key, value) ->
            val clean = value.trim()
            if (key in phoneKeys && clean.isNotBlank()) names[key] = clean
        }
        calls.groupBy { HomeCallPageLoader.noteKey(it.number) }.forEach { (key, rows) ->
            if (key.isBlank() || names[key].orEmpty().isNotBlank()) return@forEach
            rows.firstNotNullOfOrNull { row ->
                row.name.trim().takeIf { it.isNotBlank() && PhoneNormalizer.key(it) != key }
            }?.let { names[key] = it }
        }
        names.forEach { (key, value) -> rememberedNames[key] = value }
        while (rememberedNames.size > MAX_REMEMBERED_NAMES) {
            rememberedNames.remove(rememberedNames.entries.first().key)
        }

        val contactNotes = when (mode) {
            HomeRenderMergeMode.AUTHORITATIVE -> incoming.contactNotesByNumber
            HomeRenderMergeMode.LOCAL_GENERAL_AUTHORITATIVE -> linkedMapOf<String, String>().apply {
                phoneKeys.forEach { key ->
                    val merged = HomeGeneralNoteBundle.replaceLocal(
                        existing = currentContactNotes[key],
                        localValue = incoming.contactNotesByNumber[key],
                    )
                    if (merged.isNotBlank()) put(key, merged)
                }
            }
            else -> linkedMapOf<String, String>().apply {
                currentContactNotes.forEach { (key, value) -> if (key in phoneKeys) put(key, value) }
                incoming.contactNotesByNumber.forEach { (key, value) -> put(key, value) }
            }
        }
        val callNotes = when (mode) {
            HomeRenderMergeMode.AUTHORITATIVE -> incoming.callNotesByCall
            else -> linkedMapOf<String, HomeCallNote>().apply {
                currentCallNotes.forEach { (key, value) -> if (key in callKeys) put(key, value) }
                incoming.callNotesByCall.forEach { (key, value) -> put(key, value) }
            }
        }
        return HomeRenderState(contactNotes, names, callNotes)
    }
}
