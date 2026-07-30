package com.onlineimoti.calllog

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Presentation controller for [ContactNotesActivity]. */
internal class ContactNotesRestoredController(
    internal val activity: ContactNotesActivity,
) {
    internal var phone = ""
    internal var titleText = ""
    internal var crmSyncBusy = false
    internal var pullRefreshRequested = false
    internal var skipNextResumeRefresh = true
    internal var listMode = ContactHistoryListMode.NOTES_AND_SMS
    internal val handler = Handler(Looper.getMainLooper())
    internal val crmSyncExecutor = Executors.newSingleThreadExecutor()
    internal val delayedServerRefresh = Runnable {
        if (!activity.isFinishing && !activity.isDestroyed) historyController.refreshServer(phone)
    }
    internal val externalActions by lazy { ContactNotesExternalActions(activity) }
    internal val headerUi by lazy { ContactNotesHeaderUi(activity, ::dp) }
    internal val phaseUi by lazy { ContactNegotiationPhaseUi(activity, ::dp) }
    internal val historyController by lazy {
        CallReportMergedHistoryController(
            activity = activity,
            headerUi = headerUi,
            dp = ::dp,
            roundedRect = ::roundedRect,
            rerender = { render() },
        )
    }
    internal val edgePaging by lazy {
        HistoryEdgePagingController(
            canPrevious = {
                when (listMode) {
                    ContactHistoryListMode.NOTES_AND_SMS -> historyController.canPreviousNotesPage()
                    ContactHistoryListMode.FULL_LOG -> historyController.canPreviousFullLogPage()
                }
            },
            canNext = {
                when (listMode) {
                    ContactHistoryListMode.NOTES_AND_SMS -> historyController.canNextNotesPage()
                    ContactHistoryListMode.FULL_LOG -> historyController.canNextFullLogPage()
                }
            },
            previousPage = {
                when (listMode) {
                    ContactHistoryListMode.NOTES_AND_SMS -> historyController.previousNotesPage()
                    ContactHistoryListMode.FULL_LOG -> historyController.previousFullLogPage()
                }
            },
            nextPage = {
                when (listMode) {
                    ContactHistoryListMode.NOTES_AND_SMS -> historyController.nextNotesPage()
                    ContactHistoryListMode.FULL_LOG -> historyController.nextFullLogPage()
                }
            },
            resetPage = {
                historyController.resetNotesPage()
                historyController.resetFullLogPage()
            },
            pageReady = { !historyController.isLoading() },
        )
    }
    internal val stickyHistoryUi by lazy { ContactNotesStickyHistoryUi(activity) }
    internal val generalNoteSectionUi by lazy {
        CompanyScopedGeneralNoteSectionUi(
            activity = activity,
            headerUi = headerUi,
            cards = ContactNotesCards(activity, ::dp, ::roundedRect, headerUi::directionArrowLabel),
            dp = ::dp,
            roundedRect = ::roundedRect,
        )
    }

    fun onCreate(intent: Intent?) {
        handler.removeCallbacks(delayedServerRefresh)
        listMode = if (
            intent?.getStringExtra(ContactNotesActivity.EXTRA_INITIAL_LIST_MODE) == ContactNotesActivity.LIST_MODE_FULL_LOG
        ) {
            ContactHistoryListMode.FULL_LOG
        } else {
            ContactHistoryListMode.NOTES_AND_SMS
        }
        edgePaging.reset()
        stickyHistoryUi.resetScrollPosition()
        skipNextResumeRefresh = true
        phone = intent?.getStringExtra(ContactNotesActivity.EXTRA_PHONE).orEmpty()
        titleText = intent?.getStringExtra(ContactNotesActivity.EXTRA_TITLE).orEmpty().ifBlank {
            phone.ifBlank { activity.getString(R.string.dynamic_notes_default_title) }
        }
        historyController.loadOnce(phone)
        render()
    }

    fun onResume() {
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false
            return
        }
        // Ordinary navigation back to History needs one refresh, not a second confirmation request.
        refreshHistoryInBackground(scheduleConfirmationRefresh = false)
    }

    fun onDataChanged() {
        // A real write may reach the provider/server just after the first callback, so confirm once later.
        refreshHistoryInBackground(scheduleConfirmationRefresh = true)
    }

    fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stickyHistoryUi.release()
        edgePaging.release()
        crmSyncExecutor.shutdownNow()
        historyController.release()
    }

    internal fun refreshHistoryInBackground(scheduleConfirmationRefresh: Boolean) {
        if (phone.isBlank()) return
        historyController.refreshLocal(phone)
        historyController.refreshServer(phone)
        if (scheduleConfirmationRefresh) {
            handler.removeCallbacks(delayedServerRefresh)
            handler.postDelayed(delayedServerRefresh, SERVER_CONFIRMATION_REFRESH_DELAY_MS)
        }
    }

    internal fun refreshFromPull() {
        if (phone.isBlank()) {
            pullRefreshRequested = false
            render()
            return
        }
        pullRefreshRequested = true
        // Even unchanged data must produce one final render to stop the pull-refresh indicator.
        historyController.forceNextRenderAfterDataReady()
        refreshHistoryInBackground(scheduleConfirmationRefresh = false)
        render()
    }

    internal fun selectListMode(mode: ContactHistoryListMode) {
        if (mode == listMode) return
        edgePaging.release()
        listMode = mode
        stickyHistoryUi.resetScrollPosition()
        render()
    }

    internal fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    internal fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val SERVER_CONFIRMATION_REFRESH_DELAY_MS = 1_500L
    }
}
