package com.onlineimoti.calllog

import android.app.Service
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast

internal class PostCallCalendarActions(
    private val service: Service,
    private val phone: () -> String,
    private val removeOverlay: () -> Unit,
    private val stopOverlay: () -> Unit,
) {
    fun openCalendarEvent(
        displayName: String,
        generalNotes: Collection<String>,
        currentCallNote: String?,
    ) {
        val phoneValue = phone()
        val safeName = displayName.ifBlank { phoneValue.ifBlank { "контакт" } }
        val eventTitle = "Среща с $safeName"
        val contactDescription = buildString {
            appendLine("Име: $safeName")
            if (phoneValue.isNotBlank()) appendLine("Телефон: $phoneValue")
        }.trim()
        val description = ContactNoteCalendarContent.appendNotes(
            baseDescription = contactDescription,
            generalNotes = generalNotes,
            currentCallNote = currentCallNote,
            generalHeading = if (AppLocaleText.isBulgarian()) "Основни бележки" else "General notes",
            callHeading = if (AppLocaleText.isBulgarian()) "Бележка от разговора" else "Call note",
        )
        val begin = System.currentTimeMillis() + 60 * 60 * 1000L
        val end = begin + 60 * 60 * 1000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, eventTitle)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            service.startActivity(intent)
            removeOverlay()
            stopOverlay()
        }.onFailure {
            Toast.makeText(service, "Няма намерено приложение Календар", Toast.LENGTH_SHORT).show()
        }
    }
}
