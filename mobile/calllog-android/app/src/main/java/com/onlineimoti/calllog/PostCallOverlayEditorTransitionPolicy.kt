package com.onlineimoti.calllog

/** Defines safe transitions between an in-call editor and the note bubble. */
internal object PostCallOverlayEditorTransitionPolicy {
    fun keepEditorWhenCallEnds(editorIsVisible: Boolean): Boolean = editorIsVisible

    fun showActiveCallBubbleAfterEditorClosed(callIsActive: Boolean): Boolean = callIsActive
}
