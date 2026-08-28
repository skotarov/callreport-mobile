package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeServerSearchNoteRetentionTest {
    @Test
    fun `keeps a matched server search note when history has no general note`() {
        val call = PhoneCallRecord(
            number = "+359 88 123 4567",
            name = "",
            direction = "",
            startedAt = 0L,
            durationSeconds = 0L,
            searchSnippet = "Бележка от друг фирмен профил",
        )

        val snippets = HomeServerSearchNoteRetention.snippetsByPhoneKey(listOf(call))

        assertEquals(
            "☁ Бележка от друг фирмен профил",
            HomeServerSearchNoteRetention.preferredServerValue(null, snippets["881234567"]),
        )
    }

    @Test
    fun `uses a canonical history note when available`() {
        assertEquals(
            "☁ По-нова сървърна бележка",
            HomeServerSearchNoteRetention.preferredServerValue(
                canonicalValue = "☁ По-нова сървърна бележка",
                matchedSearchSnippet = "☁ Намерената бележка",
            ),
        )
    }
}
