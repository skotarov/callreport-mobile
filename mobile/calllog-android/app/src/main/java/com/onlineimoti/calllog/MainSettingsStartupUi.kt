package com.onlineimoti.calllog

import android.content.Intent
import android.view.View
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal object MainSettingsStartupUi {
    fun configureBuildSpecificSettings(
        binding: ActivityMainBinding,
        defaultSmsSettingsController: DefaultSmsSettingsController,
        callScreeningIntegrationSettingsController: CallScreeningIntegrationSettingsController,
    ) {
        val permissionsSection =
            binding.settingsApplicationGroup.permissionsSection.statusSmsPermissionsSection.root
        if (DistributionCapabilities.isPlayBusinessBuild) {
            binding.settingsMenuGroup.settingsApplicationButton.visibility = View.GONE
            binding.settingsMenuGroup.settingsPopupButton.visibility = View.GONE
            binding.settingsMenuGroup.settingsRmContactsButton.visibility = View.GONE
            binding.settingsMenuGroup.settingsDataArchiveButton.visibility = View.GONE
            binding.settingsApplicationGroup.root.visibility = View.GONE
            binding.settingsPopupGroup.root.visibility = View.GONE
            binding.settingsRmContactsGroup.root.visibility = View.GONE
            binding.settingsDataArchiveGroup.root.visibility = View.GONE
            return
        }
        permissionsSection.visibility = View.VISIBLE
        defaultSmsSettingsController.wire()
        callScreeningIntegrationSettingsController.wire()
    }

    fun openRequestedSection(binding: ActivityMainBinding, intent: Intent?): Boolean {
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
}
