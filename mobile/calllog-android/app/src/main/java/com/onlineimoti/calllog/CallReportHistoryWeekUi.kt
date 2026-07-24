package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/** Shared weekly heading used by both History lists. */
internal class CallReportHistoryWeekUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun currentWeekSerial(): Long? = weekStartSerial(System.currentTimeMillis())

    fun weekStartSerial(timestampMs: Long): Long? =
        weekStartCalendar(timestampMs)?.let(::calendarDaySerial)

    fun separator(timestampMs: Long, relativeWeeks: Long): TextView {
        return StickyGroupHeaderUi.mark(TextView(activity).apply {
            text = "${relativeWeeksLabel(relativeWeeks)} - ${weekDateRange(timestampMs)}"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(activity.getColor(R.color.callreport_icon_background))
            gravity = Gravity.CENTER_VERTICAL
            background = null
            setPadding(dp(10), dp(10), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
        })
    }

    private fun weekDateRange(timestampMs: Long): String {
        val start = weekStartCalendar(timestampMs) ?: return ""
        val end = (start.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, DAYS_PER_WEEK.toInt() - 1)
        }
        return if (start.get(Calendar.YEAR) == end.get(Calendar.YEAR)) {
            "${shortDate(start)}-${shortDate(end)} ${end.get(Calendar.YEAR)}"
        } else {
            "${shortDate(start)} ${start.get(Calendar.YEAR)}-${shortDate(end)} ${end.get(Calendar.YEAR)}"
        }
    }

    private fun shortDate(calendar: Calendar): String {
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = MONTHS_BG.getOrElse(calendar.get(Calendar.MONTH)) { "" }
        return "$day $month"
    }

    private fun relativeWeeksLabel(weeks: Long): String = when {
        weeks == 0L -> "Тази седмица"
        weeks == 1L -> "предната седмица"
        weeks > 1L -> "преди ${durationParts(weeks)}"
        weeks == -1L -> "следващата седмица"
        else -> "след ${durationParts(-weeks)}"
    }

    /**
     * The list is grouped by exact weeks, so larger periods stay predictable:
     * 4 weeks = 1 month and 52 weeks = 1 year.
     */
    private fun durationParts(totalWeeks: Long): String {
        val years = totalWeeks / WEEKS_PER_YEAR
        val afterYears = totalWeeks % WEEKS_PER_YEAR
        val months = afterYears / WEEKS_PER_MONTH
        val weeks = afterYears % WEEKS_PER_MONTH
        val parts = buildList {
            if (years > 0L) add("$years г.")
            if (months > 0L) add("$months мес.")
            if (weeks > 0L) add("$weeks седм.")
        }
        return when (parts.size) {
            0 -> "0 седм."
            1 -> parts[0]
            2 -> "${parts[0]} и ${parts[1]}"
            else -> "${parts[0]} ${parts[1]} и ${parts[2]}"
        }
    }

    private fun weekStartCalendar(timestampMs: Long): Calendar? {
        if (timestampMs <= 0L) return null
        return Calendar.getInstance().apply {
            timeInMillis = timestampMs
            val daysSinceMonday =
                (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + DAYS_PER_WEEK.toInt()) %
                    DAYS_PER_WEEK.toInt()
            add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun calendarDaySerial(calendar: Calendar): Long {
        val yearBefore = (calendar.get(Calendar.YEAR) - 1).toLong()
        val daysBeforeYear =
            365L * yearBefore + yearBefore / 4L - yearBefore / 100L + yearBefore / 400L
        return daysBeforeYear + calendar.get(Calendar.DAY_OF_YEAR).toLong() - 1L
    }

    companion object {
        const val DAYS_PER_WEEK = 7L
        private const val WEEKS_PER_MONTH = 4L
        private const val WEEKS_PER_YEAR = 52L
        private val MONTHS_BG = listOf(
            "Яну", "Фев", "Мар", "Апр", "Май", "Юн",
            "Юл", "Авг", "Сеп", "Окт", "Ное", "Дек",
        )
    }
}
