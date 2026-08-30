package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent

/** Opens the native main-note editor with the company chosen from History. */
internal object CompanyMainNoteEditorLauncher {
    const val EXTRA_COMPANY_ID = "company_main_note_id"

    fun start(
        context: Context,
        phone: String,
        title: String,
        companyId: String,
        initialNoteText: String = "",
        initialServerClientEventId: String = "",
    ) {
        val intent = ExternalLaunchNavigation.apply(
            Intent(context, ContactNoteEditActivity::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_GENERAL_NOTE)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, title)
                .putExtra(EXTRA_COMPANY_ID, companyId.trim())
                .putExtra(CallNoteEditorLauncher.EXTRA_INITIAL_NOTE_TEXT, initialNoteText)
                .putExtra(CallNoteEditorLauncher.EXTRA_SERVER_CLIENT_EVENT_ID, initialServerClientEventId)
        )
        context.startActivity(intent)
    }
}
