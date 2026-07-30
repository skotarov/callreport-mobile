package com.onlineimoti.calllog

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat

internal fun MainPermissionFlowController.requestSmsSetup() {
    if (!isDefaultSmsApp()) {
        setStatus("Избери Relationship Manager като SMS приложение. След това Android ще поиска SMS разрешенията с обикновен диалог.")
        requestDefaultSmsRole()
        return
    }
    if (!hasSmsPermissions()) {
        setStatus(activity.getString(R.string.permission_flow_request_from_dialog, "SMS"))
        requestSmsPermissions()
        return
    }
    continueAfterSmsSetup()
}

internal fun MainPermissionFlowController.continueAfterSmsSetup() {
    if (isRunning) {
        requestNextStep()
    } else {
        setStatus(activity.getString(R.string.settings_sms_role_active))
    }
}

internal fun MainPermissionFlowController.smsSetupIsComplete(): Boolean = isDefaultSmsApp() && hasSmsPermissions()

internal fun MainPermissionFlowController.requestRuntimePermission(permission: String, status: String, label: String) {
    permissionRequests.edit().putBoolean(permission, true).apply()
    lastRequestedRuntimePermission = permission
    lastRequestedRuntimePermissionLabel = label
    setStatus(status)
    requestPermissionLauncher.launch(permission)
}

internal fun MainPermissionFlowController.canShowPermissionDialog(permission: String): Boolean {
    return !permissionRequests.getBoolean(permission, false) ||
        ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

internal fun MainPermissionFlowController.sharedStorageSettingsIntent(): Intent {
    return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = Uri.parse("package:${activity.packageName}")
    }
}

internal fun MainPermissionFlowController.reportUnavailableCallScreening() {
    setStatus(activity.getString(R.string.permission_flow_screening_unavailable))
    isRunning = false
    refreshPermissionSummary()
}

internal fun MainPermissionFlowController.permissionLabel(permission: String, fallback: String): String = when (permission) {
    Manifest.permission.POST_NOTIFICATIONS -> activity.getString(R.string.permission_label_notifications)
    Manifest.permission.READ_PHONE_STATE -> activity.getString(R.string.permission_label_phone)
    Manifest.permission.READ_CALL_LOG -> activity.getString(R.string.permission_label_call_log)
    Manifest.permission.READ_CONTACTS -> activity.getString(R.string.permission_label_contacts_read)
    Manifest.permission.WRITE_CONTACTS -> activity.getString(R.string.permission_label_contacts_write)
    else -> fallback
}

internal fun MainPermissionFlowController.isCorporateTelephonyPermission(permission: String): Boolean {
    return permission == Manifest.permission.READ_PHONE_STATE || permission == Manifest.permission.READ_CALL_LOG
}

internal fun MainPermissionFlowController.overlayPopupsSelected(): Boolean = ConfigStore.load(activity).useOverlayPopups

internal fun MainPermissionFlowController.hasCallScreeningRole(): Boolean = MainPermissionChecks.hasCallScreeningRole(activity)

internal fun MainPermissionFlowController.finishFlowWithSuccess() {
    isRunning = false
    setStatus(activity.getString(R.string.permission_flow_success, LocalNotesFileStore.activeRootPath(activity)))
    refreshPermissionSummary()
}

internal fun MainPermissionFlowController.finishFlowWithoutStatus() {
    isRunning = false
    refreshPermissionSummary()
}
