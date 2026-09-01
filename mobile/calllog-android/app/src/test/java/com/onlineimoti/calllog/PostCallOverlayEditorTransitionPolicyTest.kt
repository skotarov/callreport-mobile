package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostCallOverlayEditorTransitionPolicyTest {
    @Test
    fun callEndingDoesNotDismissAnEditorThatIsBeingWritten() {
        assertTrue(PostCallOverlayEditorTransitionPolicy.keepEditorWhenCallEnds(editorIsVisible = true))
    }

    @Test
    fun callEndingStillShowsPostCallBubbleWhenNoEditorIsOpen() {
        assertFalse(PostCallOverlayEditorTransitionPolicy.keepEditorWhenCallEnds(editorIsVisible = false))
    }

    @Test
    fun closingSavedEditorDuringActiveCallReturnsToNoteBubble() {
        assertTrue(PostCallOverlayEditorTransitionPolicy.showActiveCallBubbleAfterEditorClosed(callIsActive = true))
    }
}
