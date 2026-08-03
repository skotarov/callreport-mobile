package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMergeNamePolicyTest {
    @Test
    fun `offers only the two existing non-empty names`() {
        assertEquals(
            listOf("Current Name", "Existing Name"),
            ProfileMergeNamePolicy.options(" Current Name ", "Existing Name"),
        )
    }

    @Test
    fun `deduplicates equal names and does not invent a third choice`() {
        assertEquals(
            listOf("Same Name"),
            ProfileMergeNamePolicy.options("Same Name", " Same Name "),
        )
    }

    @Test
    fun `accepts only an exact offered name`() {
        val options = ProfileMergeNamePolicy.options("Current", "Existing")
        assertTrue(ProfileMergeNamePolicy.isAllowed("Current", options))
        assertTrue(ProfileMergeNamePolicy.isAllowed(" Existing ", options))
        assertFalse(ProfileMergeNamePolicy.isAllowed("Third", options))
        assertFalse(ProfileMergeNamePolicy.isAllowed("", options))
    }
}
