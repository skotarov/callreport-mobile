package com.onlineimoti.calllog

import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal class CallScreeningIntegrationSettingsController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val requestCallScreeningRole: () -> Unit,
) {
    private val section get() = binding.settingsRmContactsGroup.callScreeningIntegrationSection

    fun wire() {
        section.callScreeningIntegrationButton.setOnClickListener { requestCallScreeningRole() }
    }

    fun refresh() {
        val active = MainPermissionChecks.hasCallScreeningRole(activity)
        val current = ConfigStore.load(activity)
        if (current.useCallScreening != active) {
            ConfigStore.save(activity, current.copy(useCallScreening = active))
        }
        section.callScreeningIntegrationStatusText.text = activity.getString(
            if (active) R.string.call_screening_integration_active else R.string.call_screening_integration_inactive,
        )
        section.callScreeningIntegrationButton.text = activity.getString(R.string.call_screening_integration_activate)
    }
}
