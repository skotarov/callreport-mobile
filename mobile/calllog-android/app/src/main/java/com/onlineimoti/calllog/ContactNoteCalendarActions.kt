package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast

/** Creates a calendar draft with the same contextual information as the note editor. */
internal object ContactNoteCalendarActions {
    fun open(
        activity: Activity,
        titleText: String,
        phone: String,
        isGeneralNote: Boolean,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        generalNotes: Collection<String>,
        currentCallNote: String?,
    ) {
        val safeName = titleText.ifBlank { phone.ifBlank { activity.getString(R.string.dynamic_calendar_default_contact) } }
        val contactDescription = buildString {
            appendLine(activity.getString(R.string.dynamic_calendar_name_line, safeName))
            if (phone.isNotBlank()) appendLine(activity.getString(R.string.dynamic_calendar_phone_line, phone))
            if (!isGeneralNote && callAt > 0L) {
                val callInfo = listOf(
                    PhoneCallReader.directionLabel(direction),
                    PhoneCallReader.formatStartedAt(callAt),
                    PhoneCallReader.formatDuration(durationSeconds),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                if (callInfo.isNotBlank()) appendLine(activity.getString(R.string.dynamic_calendar_call_line, callInfo))
            }
        }.trim()
        val description = ContactNoteCalendarContent.appendNotes(
            baseDescription = contactDescription,
            generalNotes = generalNotes,
            currentCallNote = currentCallNote,
            generalHeading = activity.getString(R.string.dynamic_note_general_title),
            callHeading = activity.getString(R.string.dynamic_note_call_title),
        )
        val begin = System.currentTimeMillis() + 60 * 60 * 1000L
        val end = begin + 60 * 60 * 1000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, activity.getString(R.string.dynamic_calendar_event_title, safeName))
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, activity.getString(R.string.dynamic_calendar_app_not_found), Toast.LENGTH_SHORT).show()
        }
    }
}
