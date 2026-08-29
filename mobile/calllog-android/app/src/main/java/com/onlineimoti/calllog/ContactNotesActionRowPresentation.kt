package com.onlineimoti.calllog

/** Shared measurements for the regular and pinned History action-card variants. */
internal data class ContactNotesActionRowPresentation(
    val showLabels: Boolean,
    val cardHeightDp: Int,
    val hostHeightDp: Int,
)

internal object ContactNotesActionRowPresentations {
    private const val CARD_HEIGHT_DP = 70
    private const val HOST_VERTICAL_SPACE_DP = 8

    val normal = ContactNotesActionRowPresentation(
        showLabels = true,
        cardHeightDp = CARD_HEIGHT_DP,
        hostHeightDp = CARD_HEIGHT_DP + HOST_VERTICAL_SPACE_DP,
    )

    // The fixed row is the same full action card, including the action labels.
    val sticky = normal
}
