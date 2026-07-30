package com.onlineimoti.calllog

import java.util.Locale
import kotlin.math.ceil

/** Pure countdown helpers used by the OTP dialog and unit tests. */
internal object ProfileOtpTimer {
    /** The server is authoritative; remaining seconds avoid client/server clock differences. */
    fun deadline(expiresAtMs: Long, openedAtMs: Long, remainingSeconds: Long = -1L): Long = when {
        remainingSeconds >= 0L -> openedAtMs + remainingSeconds * 1000L
        expiresAtMs > 0L -> expiresAtMs
        else -> 0L
    }

    fun remainingMs(deadlineMs: Long, nowMs: Long): Long =
        (deadlineMs - nowMs).coerceAtLeast(0L)

    fun format(remainingMs: Long): String {
        val totalSeconds = ceil(remainingMs.coerceAtLeast(0L) / 1000.0).toLong()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
