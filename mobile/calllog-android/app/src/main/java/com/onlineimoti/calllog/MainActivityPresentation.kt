package com.onlineimoti.calllog

import android.content.Intent

internal fun MainActivity.configureBuildSpecificSettings() {
    val permissionsSection = binding.settingsApplicationGroup.permissionsSection.statusSmsPermissionsSection.root
    if (DistributionCapabilities.isPlayBusinessBuild) {
        binding.settingsMenuGroup.settingsApplicationButton.visibility = android.view.View.GONE
        binding.settingsMenuGroup.settingsPopupButton.visibility = android.view.View.GONE
        binding.settingsMenuGroup.settingsRmContactsButton.visibility = android.view.View.GONE
        binding.settingsMenuGroup.settingsDataArchiveButton.visibility = android.view.View.GONE
        binding.settingsApplicationGroup.root.visibility = android.view.View.GONE
        binding.settingsPopupGroup.root.visibility = android.view.View.GONE
        binding.settingsRmContactsGroup.root.visibility = android.view.View.GONE
        binding.settingsDataArchiveGroup.root.visibility = android.view.View.GONE
        return
    }
    permissionsSection.visibility = android.view.View.VISIBLE
    defaultSmsSettingsController.wire()
    callScreeningIntegrationSettingsController.wire()
}

internal fun MainActivity.openRequestedSettingsSection(intent: Intent?): Boolean {
    return when {
        intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_SERVER, false) == true -> {
            binding.settingsMenuGroup.settingsServerButton.performClick()
            true
        }
        intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_REGISTRATION, false) == true -> {
            binding.settingsMenuGroup.settingsRegistrationButton.performClick()
            true
        }
        else -> false
    }
}
