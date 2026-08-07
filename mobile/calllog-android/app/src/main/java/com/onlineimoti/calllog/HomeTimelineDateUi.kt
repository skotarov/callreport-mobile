package com.onlineimoti.calllog

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Calendar-safe labels used by the Home call timeline. */
internal object HomeTimelineDateUi {
    fun relativeDaysLabel(context: Context, days: Long): String = when {
        days == 0L -> context.getString(R.string.runtime_timeline_today)
        days == 1L -> context.getString(R.string.runtime_timeline_yesterday)
        days > 1L -> context.getString(R.string.runtime_timeline_days_ago, days)
        days == -1L -> context.getString(R.string.runtime_timeline_tomorrow)
        else -> context.getString(R.string.runtime_timeline_in_days, -days)
    }

    /** Human-first daily heading, matching History's relative-first group labels. */
    fun dayGroupLabel(context: Context, timestampMs: Long, relativeDays: Long): String {
        return "${relativeDaysLabel(context, relativeDays)} - ${calendarDateLabel(timestampMs)}"
    }

    private fun calendarDateLabel(timestampMs: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return if (AppLocaleText.isBulgarian()) {
            val locale = Locale("bg", "BG")
            val weekday = SimpleDateFormat("EEEE", locale).format(calendar.time)
            val month = SimpleDateFormat("MMMM", locale)
                .format(calendar.time)
                .replaceFirstChar { character ->
                    if (character.isLowerCase()) character.titlecase(locale) else character.toString()
                }
            buildString {
                append(weekday)
                append(", ")
                append(bulgarianOrdinalDay(calendar.get(Calendar.DAY_OF_MONTH)))
                append(' ')
                append(month)
                if (calendar.get(Calendar.YEAR) != currentYear) {
                    append(' ')
                    append(calendar.get(Calendar.YEAR))
                }
            }
        } else {
            val pattern = if (calendar.get(Calendar.YEAR) == currentYear) {
                "EEEE, MMMM d"
            } else {
                "EEEE, MMMM d yyyy"
            }
            SimpleDateFormat(pattern, Locale.US).format(calendar.time)
        }
    }

    /** Uses the Bulgarian ordinal endings requested for calendar days: 7-ми, 6-ти, 21-ви, etc. */
    private fun bulgarianOrdinalDay(day: Int): String {
        val suffix = when (day) {
            1, 21, 31 -> "ви"
            2, 22 -> "ри"
            7, 8, 27, 28 -> "ми"
            else -> "ти"
        }
        return "$day-$suffix"
    }

    /** Calendar-day serial avoids daylight-saving-time errors around midnight. */
    fun localDaySerial(timestampMs: Long): Long? {
        if (timestampMs <= 0L) return null
        val calendar = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val yearBefore = (calendar.get(Calendar.YEAR) - 1).toLong()
        val daysBeforeYear = 365L * yearBefore + yearBefore / 4L - yearBefore / 100L + yearBefore / 400L
        return daysBeforeYear + calendar.get(Calendar.DAY_OF_YEAR).toLong() - 1L
    }
}
