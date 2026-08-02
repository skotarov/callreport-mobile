package com.onlineimoti.calllog

import android.graphics.Color

internal data class NoteCardColors(
    val text: Int,
    val mutedText: Int,
    val metaText: Int,
    val background: Int,
    val border: Int,
)

internal object NoteUiStyle {
    val General = NoteCardColors(
        text = Color.rgb(92, 64, 0),
        mutedText = Color.rgb(100, 116, 139),
        metaText = Color.rgb(92, 64, 0),
        background = Color.rgb(255, 254, 240),
        border = Color.TRANSPARENT,
    )

    val Call = NoteCardColors(
        text = Color.rgb(8, 47, 73),
        mutedText = Color.rgb(7, 89, 133),
        metaText = Color.rgb(7, 89, 133),
        background = Color.rgb(224, 246, 255),
        border = Color.rgb(125, 211, 252),
    )

    /** A locally saved note that has not yet been confirmed by the server. */
    val Pending = NoteCardColors(
        text = Color.rgb(71, 85, 105),
        mutedText = Color.rgb(100, 116, 139),
        metaText = Color.rgb(100, 116, 139),
        background = Color.rgb(241, 245, 249),
        border = Color.rgb(203, 213, 225),
    )
}
