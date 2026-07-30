package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileOtpTimerTest {
    @Test
    fun missingServerDeadlineFallsBackToTenMinutes() {
        assertEquals(
            1_600_000L,
            ProfileOtpTimer.deadline(expiresAtMs = 0L, openedAtMs = 1_000_000L),
        )
    }

    @Test
    fun serverDeadlineIsAuthoritative() {
        assertEquals(
            1_420_000L,
            ProfileOtpTimer.deadline(expiresAtMs = 1_420_000L, openedAtMs = 1_000_000L),
        )
    }

    @Test
    fun expiredCountdownNeverBecomesNegative() {
        assertEquals(0L, ProfileOtpTimer.remainingMs(deadlineMs = 1_000L, nowMs = 2_000L))
    }

    @Test
    fun formatsTenMinuteCountdown() {
        assertEquals("10:00", ProfileOtpTimer.format(600_000L))
        assertEquals("09:59", ProfileOtpTimer.format(598_001L))
        assertEquals("00:00", ProfileOtpTimer.format(0L))
    }
}
