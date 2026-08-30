package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallReportServerNoteClassifierTest {
    @Test
    fun explicitCallNoteNeverFallsIntoTheYellowGeneralLane() {
        val note = CallReportHistoryEvent(
            clientEventId = "device:note:call:stable-call-id",
            communicationType = "note",
            note = "Бележка към разговора",
        )

        assertTrue(CallReportServerNoteClassifier.isExplicitCallNote(note))
        assertFalse(CallReportServerNoteClassifier.isGeneralNote(note))
    }

    @Test
    fun explicitGeneralNoteStaysInTheYellowGeneralLane() {
        val note = CallReportHistoryEvent(
            clientEventId = "device:topic:general:phone:company",
            communicationType = "note",
            note = "Основна бележка",
        )

        assertTrue(CallReportServerNoteClassifier.isGeneralNote(note))
        assertFalse(CallReportServerNoteClassifier.isConcreteCallNote(note))
    }
}
