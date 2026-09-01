package com.onlineimoti.calllog

/** Keeps the note supplied by the launch row inside its original note kind. */
internal object ContactNoteInitialValuePolicy {
    fun belongsToCurrentKind(launchedAsGeneral: Boolean, currentIsGeneral: Boolean): Boolean =
        launchedAsGeneral == currentIsGeneral
}
