package com.onlineimoti.calllog

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal class MainSettingsNavigationController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onLanguageSectionShown: () -> Unit,
) {
    private var selectedSection: SettingsSection? = null
    private var serverReturnsToRegistration = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            navigateBack()
        }
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
    }

    fun wire() {
        binding.settingsDetailBackButton.setOnClickListener { navigateBack() }
        binding.settingsMenuGroup.settingsApplicationButton.setOnClickListener { showSection(SettingsSection.STATUS) }
        binding.settingsMenuGroup.settingsPopupButton.setOnClickListener { showSection(SettingsSection.CALLS) }
        binding.settingsMenuGroup.settingsRmContactsButton.setOnClickListener { showSection(SettingsSection.INTEGRATIONS) }
        binding.settingsMenuGroup.settingsServerButton.setOnClickListener {
            val returnToRegistration = activity.intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_SERVER, false) == true
            activity.intent?.removeExtra(MainActivity.EXTRA_OPEN_SERVER)
            showSection(SettingsSection.SERVER, returnToRegistration)
        }
        binding.settingsMenuGroup.settingsRegistrationButton.setOnClickListener { showSection(SettingsSection.REGISTRATION) }
        binding.settingsMenuGroup.settingsDataArchiveButton.setOnClickListener { showSection(SettingsSection.DATA_AND_BACKUP) }
        binding.settingsMenuGroup.settingsGeneralButton.setOnClickListener { showSection(SettingsSection.LANGUAGE) }
        if (BuildConfig.DEBUG) {
            binding.settingsMenuGroup.settingsDebugButton.setOnClickListener { showSection(SettingsSection.DEBUG) }
        } else {
            binding.settingsMenuGroup.settingsDebugButton.visibility = View.GONE
            binding.settingsDebugGroup.root.visibility = View.GONE
        }

        val remote = binding.remoteSettingsSection
        remote.toggleAdvancedServerSettingsButton.setOnClickListener {
            val showAdvanced = remote.advancedServerSettingsGroup.visibility != View.VISIBLE
            remote.advancedServerSettingsGroup.visibility = if (showAdvanced) View.VISIBLE else View.GONE
            remote.toggleAdvancedServerSettingsButton.text = activity.getString(
                if (showAdvanced) R.string.server_advanced_settings_hide else R.string.server_advanced_settings_show,
            )
            TranslationManager.applyOverridesToViewTree(activity, remote.toggleAdvancedServerSettingsButton)
        }
        remote.saveServerSettingsButton.setOnClickListener { saveServerSettingsArchive() }
        remote.restoreServerSettingsButton.setOnClickListener { restoreServerSettingsArchive() }
    }

    fun showMenu() {
        selectedSection = null
        serverReturnsToRegistration = false
        backCallback.isEnabled = false
        binding.settingsMenuGroup.root.visibility = View.VISIBLE
        binding.settingsDescriptionText.visibility = View.VISIBLE
        binding.settingsDetailHeader.visibility = View.GONE
        binding.quickTestBar.visibility = View.GONE
        allGroupViews().forEach { it.visibility = View.GONE }
        if (!BuildConfig.DEBUG) binding.settingsDebugGroup.root.visibility = View.GONE
        scrollTop()
    }

    private fun navigateBack() {
        if (selectedSection == SettingsSection.SERVER && serverReturnsToRegistration) {
            showSection(SettingsSection.REGISTRATION)
        } else {
            showMenu()
        }
    }

    private fun saveServerSettingsArchive() {
        val code = archiveCode() ?: return
        val config = MainSettingsConfigUi.read(binding)
        ConfigStore.save(activity, config)

        ServerSettingsArchiveFile.save(activity, ConfigStore.load(activity), code)
            .onSuccess { path ->
                binding.remoteSettingsSection.serverBackupCodeInput.text?.clear()
                setStatus(activity.getString(R.string.server_settings_backup_saved, path))
            }
            .onFailure { error ->
                setStatus(activity.getString(R.string.server_settings_backup_failed, error.message.orEmpty()))
            }
    }

    private fun restoreServerSettingsArchive() {
        val code = archiveCode() ?: return
        when (val result = ServerSettingsArchiveFile.restore(activity, ConfigStore.load(activity), code)) {
            is ServerSettingsBackupStore.RestoreResult.Restored -> {
                ConfigStore.save(activity, result.config)
                MainSettingsConfigUi.hydrateServerSettings(binding, result.config)
                binding.remoteSettingsSection.serverBackupCodeInput.text?.clear()
                setStatus(activity.getString(R.string.server_settings_backup_restored, ServerSettingsArchiveFile.path(activity)))
            }
            is ServerSettingsBackupStore.RestoreResult.Failed -> {
                setStatus(activity.getString(R.string.server_settings_backup_failed, result.message))
            }
        }
    }

    private fun archiveCode(): String? {
        val input = binding.remoteSettingsSection.serverBackupCodeInput
        val code = input.text?.toString().orEmpty()
        if (code.length == 4 && code.all(Char::isDigit)) return code
        input.error = activity.getString(R.string.settings_backup_code_required)
        return null
    }

    private fun setStatus(message: String) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = message
    }

    private fun showSection(section: SettingsSection, returnToRegistration: Boolean = false) {
        if (section == SettingsSection.DEBUG && !BuildConfig.DEBUG) return
        selectedSection = section
        serverReturnsToRegistration = section == SettingsSection.SERVER && returnToRegistration
        backCallback.isEnabled = true
        binding.settingsMenuGroup.root.visibility = View.GONE
        binding.settingsDescriptionText.visibility = View.GONE
        binding.settingsDetailHeader.visibility = View.VISIBLE
        binding.settingsDetailTitle.text = activity.getString(section.titleRes)
        TranslationManager.applyOverridesToViewTree(activity, binding.settingsDetailHeader)
        allGroupViews().forEach { it.visibility = View.GONE }
        section.view(binding).visibility = View.VISIBLE
        if (section == SettingsSection.LANGUAGE) onLanguageSectionShown()
        binding.quickTestBar.visibility = if (section == SettingsSection.DEBUG && BuildConfig.DEBUG) View.VISIBLE else View.GONE
        scrollTop()
    }

    private fun allGroupViews(): List<View> = SettingsSection.values().map { it.view(binding) }

    private fun scrollTop() {
        binding.settingsScrollView.post { binding.settingsScrollView.scrollTo(0, 0) }
    }

    private enum class SettingsSection(val titleRes: Int) {
        STATUS(R.string.settings_application_section),
        CALLS(R.string.settings_popup_section),
        INTEGRATIONS(R.string.settings_integrations_section),
        SERVER(R.string.settings_server_section),
        REGISTRATION(R.string.settings_profile_companies_section),
        DATA_AND_BACKUP(R.string.settings_storage_section),
        LANGUAGE(R.string.settings_general_section),
        DEBUG(R.string.settings_debug_section);

        fun view(binding: ActivityMainBinding): View {
            return when (this) {
                STATUS -> binding.settingsApplicationGroup.root
                CALLS -> binding.settingsPopupGroup.root
                INTEGRATIONS -> binding.settingsRmContactsGroup.root
                SERVER -> binding.settingsServerGroup.root
                REGISTRATION -> binding.settingsRegistrationGroup.root
                DATA_AND_BACKUP -> binding.settingsDataArchiveGroup.root
                LANGUAGE -> binding.settingsGeneralGroup.root
                DEBUG -> binding.settingsDebugGroup.root
            }
        }
    }
}
