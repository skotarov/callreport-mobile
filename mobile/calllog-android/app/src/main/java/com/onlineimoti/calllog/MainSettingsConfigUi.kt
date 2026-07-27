package com.onlineimoti.calllog

import android.view.View
import com.onlineimoti.calllog.databinding.ActivityMainBinding

object MainSettingsConfigUi {
    fun hydrate(binding: ActivityMainBinding, config: AppConfig) {
        val popup = binding.popupSettingsSection
        val popupFilter = binding.popupContactFilterSection
        val callLog = binding.settingsPopupGroup.callLogSettingsSection
        val defaultSms = binding.settingsRmContactsGroup.defaultSmsSection
        val contactLink = binding.contactLinkSection
        val language = binding.settingsGeneralGroup.languageSettingsSection
        val tests = binding.testsSection

        hydrateServerSettings(binding, config)
        popupFilter.contactGroupsInput.setText(config.contactGroups)
        popupFilter.notifyUnknownContactsCheckBox.isChecked = config.notifyUnknownContacts
        popupFilter.notifyKnownContactsCheckBox.isChecked = config.notifyKnownContacts
        callLog.homeCallPageSizeInput.setText(config.homeCallPageSize.toString())
        when (PageLoadingModeStore.load(binding.root.context)) {
            PageLoadingModeStore.MODE_BUTTONS -> callLog.pageLoadingModeButtonsRadio.isChecked = true
            else -> callLog.pageLoadingModePrefetchRadio.isChecked = true
        }
        defaultSms.useInternalSmsComposerCheckBox.isChecked = config.useInternalSmsComposer
        defaultSms.openSmsIconToHistoryCheckBox.isChecked = config.openSmsIconToHistory
        popup.postCallTimeoutInput.setText(config.postCallPromptTimeoutSeconds.toString())
        when (config.postCallEndAction) {
            ConfigStore.POST_CALL_END_ACTION_HISTORY -> popup.postCallEndActionHistory.isChecked = true
            ConfigStore.POST_CALL_END_ACTION_NOTHING -> popup.postCallEndActionNothing.isChecked = true
            else -> popup.postCallEndActionEdit.isChecked = true
        }
        popup.overlayPopupOptionsGroup.visibility = if (config.useOverlayPopups) View.VISIBLE else View.GONE
        popup.useCustomStartPopupCheckBox.isChecked = config.useCustomStartPopup
        popup.useCustomEndPopupCheckBox.isChecked = config.useCustomEndPopup
        contactLink.useLinkedContactIntegrationCheckBox.isChecked = config.useLinkedContactIntegration
        contactLink.useContactShareIntegrationCheckBox.isChecked = config.useContactShareIntegration
        contactLink.showCrmActionButtonsCheckBox.isChecked = config.showCrmActionButtons
        contactLink.showBulkContactSyncNotificationsCheckBox.isChecked = config.showBulkContactSyncNotifications
        when (config.appLanguage) {
            ConfigStore.LANGUAGE_BG -> language.appLanguageBg.isChecked = true
            ConfigStore.LANGUAGE_EN -> language.appLanguageEn.isChecked = true
            else -> language.appLanguageSystem.isChecked = true
        }
        when (AppFontScaleStore.loadMultiplier(binding.root.context)) {
            AppFontScaleStore.LARGE -> binding.settingsGeneralGroup.fontScaleLargestRadio.isChecked = true
            AppFontScaleStore.NORMAL -> binding.settingsGeneralGroup.fontScaleLargerRadio.isChecked = true
            else -> binding.settingsGeneralGroup.fontScaleNormalRadio.isChecked = true
        }
        tests.showRmDebugBoxCheckBox.isChecked = config.showRmDebugBox
    }

    fun hydrateServerSettings(binding: ActivityMainBinding, config: AppConfig) {
        val remote = binding.remoteSettingsSection
        remote.remoteEnabledCheckBox.isChecked = config.remoteEnabled
        remote.remoteSettingsGroup.visibility = View.VISIBLE
        remote.baseUrlInput.setText(config.baseUrl)
        remote.accessTokenInput.setText(config.accessToken)
        remote.lookupPathInput.setText(config.lookupPath)
        remote.formPathInput.setText(config.formPath)
        remote.historyPathInput.setText(config.historyPath)
    }

    fun read(binding: ActivityMainBinding): AppConfig {
        val remote = binding.remoteSettingsSection
        val popup = binding.popupSettingsSection
        val popupFilter = binding.popupContactFilterSection
        val callLog = binding.settingsPopupGroup.callLogSettingsSection
        val defaultSms = binding.settingsRmContactsGroup.defaultSmsSection
        val contactLink = binding.contactLinkSection
        val language = binding.settingsGeneralGroup.languageSettingsSection
        val tests = binding.testsSection
        val currentConfig = ConfigStore.load(binding.root.context)

        return AppConfig(
            remoteEnabled = remote.remoteEnabledCheckBox.isChecked,
            baseUrl = remote.baseUrlInput.text?.toString().orEmpty(),
            accessToken = remote.accessTokenInput.text?.toString().orEmpty(),
            contactGroups = popupFilter.contactGroupsInput.text?.toString().orEmpty(),
            notifyUnknownContacts = popupFilter.notifyUnknownContactsCheckBox.isChecked,
            notifyKnownContacts = popupFilter.notifyKnownContactsCheckBox.isChecked,
            homeCallPageSize = callLog.homeCallPageSizeInput.text?.toString()?.toIntOrNull()
                ?: ConfigStore.DEFAULT_HOME_CALL_PAGE_SIZE,
            lookupPath = remote.lookupPathInput.text?.toString().orEmpty(),
            formPath = remote.formPathInput.text?.toString().orEmpty(),
            historyPath = remote.historyPathInput.text?.toString().orEmpty(),
            postCallPromptTimeoutSeconds = popup.postCallTimeoutInput.text?.toString()?.toIntOrNull()
                ?: ConfigStore.DEFAULT_POST_CALL_TIMEOUT_SECONDS,
            useOverlayPopups = currentConfig.useOverlayPopups,
            useCustomStartPopup = popup.useCustomStartPopupCheckBox.isChecked,
            useCustomEndPopup = popup.useCustomEndPopupCheckBox.isChecked,
            postCallEndAction = when {
                popup.postCallEndActionHistory.isChecked -> ConfigStore.POST_CALL_END_ACTION_HISTORY
                popup.postCallEndActionNothing.isChecked -> ConfigStore.POST_CALL_END_ACTION_NOTHING
                else -> ConfigStore.POST_CALL_END_ACTION_EDIT
            },
            contactLinkMode = ConfigStore.DEFAULT_CONTACT_LINK_MODE,
            showCrmActionButtons = contactLink.showCrmActionButtonsCheckBox.isChecked,
            showBulkContactSyncNotifications = contactLink.showBulkContactSyncNotificationsCheckBox.isChecked,
            appLanguage = when {
                language.appLanguageBg.isChecked -> ConfigStore.LANGUAGE_BG
                language.appLanguageEn.isChecked -> ConfigStore.LANGUAGE_EN
                else -> ConfigStore.LANGUAGE_SYSTEM
            },
            usePublicNotesFolder = currentConfig.usePublicNotesFolder,
            useCallScreening = currentConfig.useCallScreening,
            showRmDebugBox = tests.showRmDebugBoxCheckBox.isChecked,
            useLocalNotesStorage = currentConfig.useLocalNotesStorage,
            localNotesFolderUri = currentConfig.localNotesFolderUri,
            useFullScreenPopup = currentConfig.useFullScreenPopup,
            useInternalSmsComposer = defaultSms.useInternalSmsComposerCheckBox.isChecked,
            openSmsIconToHistory = defaultSms.openSmsIconToHistoryCheckBox.isChecked,
            useLinkedContactIntegration = contactLink.useLinkedContactIntegrationCheckBox.isChecked,
            useContactShareIntegration = contactLink.useContactShareIntegrationCheckBox.isChecked,
        )
    }
}
