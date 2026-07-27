package com.onlineimoti.calllog

import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal class ServerSyncQueueStatusController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val saveConfig: () -> AppConfig,
    private val setStatus: (String) -> Unit,
) {
    private val serverGroup
        get() = binding.settingsServerGroup

    fun wire() {
        serverGroup.retryPendingTopicNotesButton.setOnClickListener {
            val config = saveConfig()
            val count = CallReportTopicNoteOutbox.pendingCount(activity)
            if (count == 0) {
                setStatus(activity.getString(R.string.server_pending_notes_none))
                refresh()
                return@setOnClickListener
            }
            if (!CallReportRemoteAccess.isReady(config)) {
                setStatus(activity.getString(R.string.server_pending_notes_settings_required, count))
                refresh()
                return@setOnClickListener
            }
            CallReportTopicNoteOutbox.requestSyncNow(activity)
            CallReportSyncScheduler.enqueueCatchUp(activity, reason = "manual_topic_note_queue")
            setStatus(activity.getString(R.string.server_pending_notes_retry_scheduled))
            refresh()
        }
    }

    fun refresh() {
        val pending = CallReportTopicNoteOutbox.pendingCount(activity)
        val deferred = CallReportDeferredCompanyAssignmentStore.count(activity)
        val failure = CallReportTopicNoteOutbox.lastFailure(activity)
        val ready = CallReportRemoteAccess.isReady(ConfigStore.load(activity))

        serverGroup.pendingTopicNotesStatusText.text = when {
            pending == 0 && deferred == 0 -> activity.getString(R.string.server_pending_notes_none)
            pending > 0 && !ready -> activity.getString(R.string.server_pending_notes_settings_required, pending)
            pending > 0 && failure.isNotBlank() -> activity.getString(R.string.server_pending_notes_failure, pending, failure)
            pending > 0 && deferred > 0 -> activity.getString(R.string.server_pending_notes_with_deferred, pending, deferred)
            pending > 0 -> activity.getString(R.string.server_pending_notes_status, pending)
            else -> activity.getString(R.string.server_pending_notes_deferred_only, deferred)
        }
        serverGroup.retryPendingTopicNotesButton.isEnabled = pending > 0 && ready
    }
}
