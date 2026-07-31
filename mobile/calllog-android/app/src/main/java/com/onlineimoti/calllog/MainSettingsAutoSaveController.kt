package com.onlineimoti.calllog

import android.text.Editable
import android.text.TextWatcher
import android.widget.CompoundButton
import com.google.android.material.textfield.TextInputEditText
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal class MainSettingsAutoSaveController(
    private val binding: ActivityMainBinding,
    private val autoSaveSettings: () -> AppConfig,
    private val requestRemoteEnabledChange: (Boolean) -> Unit,
    private val notifyRemoteConnectionInputChanged: () -> Unit,
    private val applyLanguageIfChanged: (String) -> Unit,
    private val applyFontScaleIfChanged: (Float) -> Unit,
) {
    fun wire() {
        val remote = binding.remoteSettingsSection
        val popup = binding.popupSettingsSection
        val popupFilter = binding.popupContactFilterSection
        val callLog = binding.settingsPopupGroup.callLogSettingsSection
        val defaultSms = binding.settingsRmContactsGroup.defaultSmsSection
        val contactLink = binding.contactLinkSection
        val language = binding.settingsGeneralGroup.languageSettingsSection
        val tests = binding.testsSection

        remote.remoteEnabledCheckBox.setOnCheckedChangeListener { _, isChecked ->
            requestRemoteEnabledChange(isChecked)
        }
        listOf(
            remote.baseUrlInput,
            remote.accessTokenInput,
            remote.lookupPathInput,
        ).forEach { input -> input.watchTextChanges(notifyRemoteConnectionInputChanged) }
        listOf(
            remote.formPathInput,
            remote.historyPathInput,
            popup.postCallTimeoutInput,
            callLog.homeCallPageSizeInput,
            popupFilter.contactGroupsInput,
        ).forEach { input -> input.watchTextChanges { autoSaveSettings() } }
        binding.settingsGeneralGroup.nativeCountryCodeInput.watchTextChanges {
            PhoneCountrySettingsStore.save(
                binding.root.context,
                binding.settingsGeneralGroup.nativeCountryCodeInput.text?.toString().orEmpty(),
            )
        }

        callLog.pageLoadingModeGroup.setOnCheckedChangeListener { _, checkedId ->
            PageLoadingModeStore.save(
                binding.root.context,
                if (checkedId == callLog.pageLoadingModeButtonsRadio.id) {
                    PageLoadingModeStore.MODE_BUTTONS
                } else {
                    PageLoadingModeStore.MODE_PREFETCH
                },
            )
        }
        popup.postCallEndActionGroup.setOnCheckedChangeListener { _, _ -> autoSaveSettings() }
        popup.useCustomStartPopupCheckBox.autoSaveCheckedChanges()
        popup.useCustomEndPopupCheckBox.autoSaveCheckedChanges()
        defaultSms.useInternalSmsComposerCheckBox.autoSaveCheckedChanges()
        defaultSms.openSmsIconToHistoryCheckBox.autoSaveCheckedChanges()
        contactLink.useLinkedContactIntegrationCheckBox.autoSaveCheckedChanges()
        contactLink.useContactShareIntegrationCheckBox.autoSaveCheckedChanges()
        contactLink.showCrmActionButtonsCheckBox.autoSaveCheckedChanges()
        contactLink.showBulkContactSyncNotificationsCheckBox.autoSaveCheckedChanges()
        popupFilter.notifyUnknownContactsCheckBox.autoSaveCheckedChanges()
        popupFilter.notifyKnownContactsCheckBox.autoSaveCheckedChanges()
        tests.showRmDebugBoxCheckBox.autoSaveCheckedChanges()
        binding.settingsGeneralGroup.fontScaleGroup.setOnCheckedChangeListener { _, checkedId ->
            autoSaveSettings()
            val scale = when (checkedId) {
                binding.settingsGeneralGroup.fontScaleLargestRadio.id -> AppFontScaleStore.LARGE
                binding.settingsGeneralGroup.fontScaleLargerRadio.id -> AppFontScaleStore.NORMAL
                else -> AppFontScaleStore.SMALL
            }
            AppFontScaleStore.saveMultiplier(binding.root.context, scale)
            applyFontScaleIfChanged(scale)
        }

        language.appLanguageGroup.setOnCheckedChangeListener { _, _ ->
            val config = autoSaveSettings()
            applyLanguageIfChanged(config.appLanguage)
        }
    }

    private fun TextInputEditText.watchTextChanges(action: () -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = action()
        })
    }

    private fun CompoundButton.autoSaveCheckedChanges() {
        setOnCheckedChangeListener { _, _ -> autoSaveSettings() }
    }
}
