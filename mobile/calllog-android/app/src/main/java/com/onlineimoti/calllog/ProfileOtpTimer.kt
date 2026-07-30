package com.onlineimoti.calllog

import java.util.Locale
import kotlin.math.ceil

/** Pure countdown helpers used by the OTP dialog and unit tests. */
internal object ProfileOtpTimer {
    /** The server expiry is authoritative; missing or expired values do not start a local timer. */
    fun deadline(expiresAtMs: Long, openedAtMs: Long): Long =
        if (expiresAtMs > openedAtMs) expiresAtMs else 0L

    fun remainingMs(deadlineMs: Long, nowMs: Long): Long =
        (deadlineMs - nowMs).coerceAtLeast(0L)

    fun format(remainingMs: Long): String {
        val totalSeconds = ceil(remainingMs.coerceAtLeast(0L) / 1000.0).toLong()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
