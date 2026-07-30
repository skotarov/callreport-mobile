package com.onlineimoti.calllog

import android.content.Intent
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat

internal fun ContactNotesRestoredController.render() {
    val showPullRefresh = pullRefreshRequested && historyController.isLoading()
    if (pullRefreshRequested && !showPullRefresh) pullRefreshRequested = false
    val config = ConfigStore.load(activity)
    val crmSyncEnabled = CrmContactSyncStore.isEnabled(activity, phone)
    val crmSyncServerBacked = !crmSyncEnabled && (
        historyController.hasServerRecordsFor(phone) || historyController.hasConfirmedLocalServerNote()
    )
    val phaseControlsVisible = config.remoteEnabled && RmContactSyncLayerStore.isEnabled(activity, phone)
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(24))
        setBackgroundColor(ContextCompat.getColor(activity, R.color.calllog_bg))
    }
    root.addView(headerUi.headerRow(
        title = titleText,
        phone = phone,
        contactExists = historyController.contactExists(),
        showRmCallLogButton = true,
        showCrmSyncButton = config.remoteEnabled,
        crmSyncEnabled = crmSyncEnabled,
        crmSyncBusy = crmSyncBusy,
        crmSyncServerBacked = crmSyncServerBacked,
        goBack = { activity.finish() },
        openDialer = { externalActions.openDialer(phone) },
        openCalendarEvent = { externalActions.openCalendarEvent(phone, titleText) },
        openDefaultContact = { externalActions.openDefaultContact(phone, titleText) },
        openRmContact = { openRmContactForm() },
        toggleCrmSync = { setCrmSyncEnabled(!CrmContactSyncStore.isEnabled(activity, phone)) },
        openRmCallLog = { openRmCallLog() },
        openRmCallLogFiltered = { selectListMode(ContactHistoryListMode.FULL_LOG) },
    ))
    root.addView(ContactNotesServerStatusUi.create(
        activity = activity,
        dp = ::dp,
        textValue = historyController.serverLoadingStatusText(),
    ))
    when (listMode) {
        ContactHistoryListMode.NOTES_AND_SMS -> {
            generalNoteSectionUi.add(
                root = root,
                localNote = historyController.localGeneralNote(),
                localNotePending = historyController.localGeneralNotePending(),
                companyScopeAvailable = historyController.companyScopeAvailable(),
                companyNotes = historyController.companyMainNotes(phone),
                unscopedServerMainNote = historyController.unscopedServerMainNote(phone),
                showCompanyNotes = historyController.hasCompanyMainNoteScope(),
                onEditCompany = { companyId -> openGeneralNoteEditor(companyId) },
                onEditUnscopedServerMainNote = { event -> openUnscopedServerMainNoteEditor(event) },
                phaseBarForCompany = if (phaseControlsVisible) {
                    { companyId -> phaseUi.phaseBar(phone, companyId, true, { render() }) }
                } else null,
            )
            historyController.addNotesSection(
                root = root,
                phone = phone,
                onEditCallNote = { note -> openCallNoteEditor(note) },
                onEditSms = { sms, companyId -> openSmsCompanyEditor(sms, companyId) },
            )
        }
        ContactHistoryListMode.FULL_LOG -> historyController.addFullLogSection(
            root = root,
            phone = phone,
            openCallNoteEditor = { call, displayName, note -> openFullLogCallNoteEditor(call, displayName, note) },
        )
    }
    CrmHistoryTextLocalizer.apply(activity, root)
    stickyHistoryUi.show(
        root = root,
        refreshing = showPullRefresh,
        onRefresh = { refreshFromPull() },
        bindPaging = edgePaging::bind,
        mode = listMode,
        onModeSelected = { mode -> selectListMode(mode) },
    )
    historyController.markRendered()
}

internal fun ContactNotesRestoredController.setCrmSyncEnabled(enabled: Boolean) {
    if (crmSyncBusy || phone.isBlank() || !ConfigStore.load(activity).remoteEnabled) return
    val requestedPhone = phone
    val busyToken = HomeBusyTooltipUi.begin(activity, HomeBusyWork.COMPANY_DATA)
    crmSyncBusy = true
    render()
    crmSyncExecutor.execute {
        val updated = runCatching {
            RmContactSyncLayerStore.setCloudSyncWithoutRmLayer(
                context = activity.applicationContext,
                phone = requestedPhone,
                enabled = enabled,
            )
        }.getOrDefault(false)
        handler.post {
            HomeBusyTooltipUi.end(activity, busyToken)
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (requestedPhone != phone) {
                crmSyncBusy = false
                return@post
            }
            crmSyncBusy = false
            val message = when {
                updated && enabled -> activity.getString(R.string.dynamic_crm_sync_turned_on)
                updated -> activity.getString(R.string.dynamic_crm_sync_turned_off)
                enabled -> activity.getString(R.string.dynamic_crm_sync_create_failed)
                else -> activity.getString(R.string.dynamic_crm_sync_clear_failed)
            }
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            historyController.forceNextRenderAfterDataReady()
            refreshHistoryInBackground(scheduleConfirmationRefresh = false)
        }
    }
}

internal fun ContactNotesRestoredController.openGeneralNoteEditor(companyId: String = "") {
    CompanyMainNoteEditorLauncher.start(activity, phone, titleText, companyId)
}

internal fun ContactNotesRestoredController.openUnscopedServerMainNoteEditor(event: CallReportHistoryEvent) {
    val clientEventId = event.clientEventId.trim()
    if (clientEventId.isBlank()) {
        Toast.makeText(activity, "Сървърната бележка няма ID за редакция.", Toast.LENGTH_SHORT).show()
        return
    }
    CallNoteEditorLauncher.startEditor(
        context = activity,
        mode = PostCallOverlayService.MODE_NOTE,
        phone = phone,
        title = titleText,
        direction = event.direction,
        callAt = event.occurredAtMs.takeIf { it > 0L } ?: event.updatedAtMs,
        durationSeconds = event.durationSeconds,
        companyId = event.companyId,
        initialNoteText = event.note,
        serverClientEventId = clientEventId,
    )
}

internal fun ContactNotesRestoredController.openCallNoteEditor(note: ContactCallNote) {
    CallNoteEditorLauncher.startEditor(
        context = activity,
        mode = PostCallOverlayService.MODE_NOTE,
        phone = phone,
        title = titleText,
        direction = note.direction,
        callAt = note.callAt,
        durationSeconds = note.durationSeconds,
        companyId = note.companyId,
        initialNoteText = note.note,
        serverClientEventId = note.serverClientEventId,
    )
}

internal fun ContactNotesRestoredController.openFullLogCallNoteEditor(
    call: PhoneCallRecord,
    displayName: String,
    note: HomeCallNote?,
) {
    CallNoteEditorLauncher.startEditor(
        context = activity,
        mode = PostCallOverlayService.MODE_NOTE,
        phone = call.number.ifBlank { phone },
        title = displayName.ifBlank { titleText },
        direction = call.direction,
        callAt = call.startedAt,
        durationSeconds = call.durationSeconds,
        companyId = note?.companyId.orEmpty(),
        initialNoteText = note?.text.orEmpty(),
        serverClientEventId = note?.serverClientEventId.orEmpty(),
    )
}

internal fun ContactNotesRestoredController.openSmsCompanyEditor(sms: SmsMessageRecord, companyId: String) {
    if (!CallReportRemoteAccess.isReady(ConfigStore.load(activity))) {
        Toast.makeText(activity, "За SMS фирма включи и настрой Server", Toast.LENGTH_SHORT).show()
        return
    }
    SmsCompanyAssignmentDialog(activity, ::dp, ::roundedRect).show(
        phone = phone,
        title = titleText,
        sms = sms,
        initialCompanyId = companyId,
        onSaved = {
            refreshHistoryInBackground(scheduleConfirmationRefresh = true)
        },
    )
}

internal fun ContactNotesRestoredController.openRmContactForm() {
    RmContactFormDialog(activity).show(
        phone = phone,
        fallbackTitle = titleText,
        onSaved = {
            refreshHistoryInBackground(scheduleConfirmationRefresh = true)
        },
    )
}

internal fun ContactNotesRestoredController.openRmCallLog() {
    activity.startActivity(Intent(activity, HomeActivity::class.java))
}
