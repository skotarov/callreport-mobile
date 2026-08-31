package com.onlineimoti.calllog

import android.content.Context

/** Builds the note portion of a calendar draft without borrowing a call note from History. */
internal object ContactNoteCalendarContent {
    fun storedGeneralNotes(context: Context, phone: String, companyIds: Collection<String>): List<String> {
        if (phone.isBlank()) return emptyList()
        val companyIdsForAccount = CallReportTopicCompaniesCache.read(
            context.applicationContext,
            ConfigStore.load(context.applicationContext),
        )?.companies.orEmpty().map { it.id }
        return buildList {
            add(ContactNoteReader.generalNoteForPhone(context, phone))
            (companyIds + companyIdsForAccount)
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .forEach { companyId -> add(CallReportCompanyGeneralNoteStore.noteFor(context, phone, companyId)) }
        }.map(String::trim).filter(String::isNotBlank).distinct()
    }

    fun appendNotes(
        baseDescription: String,
        generalNotes: Collection<String>,
        currentCallNote: String?,
        generalHeading: String,
        callHeading: String,
    ): String = buildString {
        append(baseDescription.trim())
        val yellowNotes = generalNotes.map(String::trim).filter(String::isNotBlank).distinct()
        if (yellowNotes.isNotEmpty()) {
            if (isNotEmpty()) appendLine().appendLine()
            appendLine("$generalHeading:")
            yellowNotes.forEach { appendLine("• $it") }
        }
        currentCallNote?.trim()?.takeIf { it.isNotBlank() }?.let { blueNote ->
            if (isNotEmpty()) appendLine()
            appendLine("$callHeading:")
            append(blueNote)
        }
    }.trim()
}
