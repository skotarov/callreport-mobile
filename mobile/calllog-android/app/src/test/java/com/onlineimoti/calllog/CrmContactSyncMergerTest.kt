package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmContactSyncMergerTest {
    private val key = "888123456"

    @Test
    fun newerLocalUnmarkIsUploaded() {
        val local = record(active = false, updatedAt = 200L)
        val server = record(active = true, updatedAt = 100L)

        val plan = CrmContactSyncMerger.plan(
            localRecords = mapOf(key to local),
            pendingRecords = mapOf(key to local),
            serverRecords = mapOf(key to server),
            serverIncludesInactive = true,
        )

        assertFalse(plan.effectiveRecords.getValue(key).active)
        assertEquals(listOf(local), plan.outgoingRecords)
        assertTrue(plan.pendingKeysToClear.isEmpty())
    }

    @Test
    fun newerServerChangeOverridesPendingLocalState() {
        val local = record(active = false, updatedAt = 100L)
        val server = record(active = true, updatedAt = 200L)

        val plan = CrmContactSyncMerger.plan(
            localRecords = mapOf(key to local),
            pendingRecords = mapOf(key to local),
            serverRecords = mapOf(key to server),
            serverIncludesInactive = true,
        )

        assertTrue(plan.effectiveRecords.getValue(key).active)
        assertTrue(plan.outgoingRecords.isEmpty())
        assertEquals(setOf(key), plan.pendingKeysToClear)
    }

    @Test
    fun legacyActiveSnapshotAbsenceClearsNonPendingLocalMarker() {
        val local = record(active = true, updatedAt = 100L)

        val plan = CrmContactSyncMerger.plan(
            localRecords = mapOf(key to local),
            pendingRecords = emptyMap(),
            serverRecords = emptyMap(),
            serverIncludesInactive = false,
        )

        assertFalse(plan.effectiveRecords.getValue(key).active)
        assertTrue(plan.outgoingRecords.isEmpty())
    }

    @Test
    fun pendingLocalMarkerMissingOnServerIsUploaded() {
        val local = record(active = true, updatedAt = 200L)

        val plan = CrmContactSyncMerger.plan(
            localRecords = mapOf(key to local),
            pendingRecords = mapOf(key to local),
            serverRecords = emptyMap(),
            serverIncludesInactive = false,
        )

        assertTrue(plan.effectiveRecords.getValue(key).active)
        assertEquals(listOf(local), plan.outgoingRecords)
    }

    @Test
    fun inactiveServerTombstoneCanWinAcrossDevices() {
        val local = record(active = true, updatedAt = 100L)
        val server = record(active = false, updatedAt = 300L)

        val plan = CrmContactSyncMerger.plan(
            localRecords = mapOf(key to local),
            pendingRecords = emptyMap(),
            serverRecords = mapOf(key to server),
            serverIncludesInactive = true,
        )

        assertFalse(plan.effectiveRecords.getValue(key).active)
        assertTrue(plan.outgoingRecords.isEmpty())
    }

    private fun record(active: Boolean, updatedAt: Long) = CrmSyncRecord(
        phone = key,
        active = active,
        updatedAtMs = updatedAt,
    )
}
