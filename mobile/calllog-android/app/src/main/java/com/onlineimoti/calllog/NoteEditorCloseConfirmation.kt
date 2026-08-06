package com.onlineimoti.calllog

import android.app.Activity
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.WindowManager

/** Confirms only when closing would discard text that has not been persisted. */
internal object NoteEditorCloseConfirmation {
    fun request(
        activity: Activity,
        hasUnsavedChanges: Boolean,
        closeWithoutSaving: () -> Unit,
    ) {
        if (!hasUnsavedChanges) {
            closeWithoutSaving()
            return
        }
        createDialog(activity, closeWithoutSaving).show()
    }

    fun requestOverlay(
        service: Service,
        hasUnsavedChanges: Boolean,
        closeWithoutSaving: () -> Unit,
    ) {
        if (!hasUnsavedChanges) {
            closeWithoutSaving()
            return
        }
        val themedContext = ContextThemeWrapper(
            service,
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
        )
        createDialog(themedContext, closeWithoutSaving).apply {
            window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            show()
        }
    }

    private fun createDialog(
        context: Context,
        closeWithoutSaving: () -> Unit,
    ): AlertDialog {
        val bulgarian = AppLocaleText.isBulgarian()
        return AlertDialog.Builder(context)
            .setMessage(if (bulgarian) "Да се затвори ли без запис?" else "Close without saving?")
            .setNegativeButton(if (bulgarian) "Остани" else "Stay", null)
            .setPositiveButton(if (bulgarian) "Затвори" else "Close") { _, _ ->
                closeWithoutSaving()
            }
            .create()
    }
}
