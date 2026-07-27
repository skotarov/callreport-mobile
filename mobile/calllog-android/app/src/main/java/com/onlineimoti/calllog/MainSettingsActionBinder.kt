package com.onlineimoti.calllog

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityMainBinding

/** Connects the Settings screen's static buttons without adding lifecycle work to MainActivity. */
internal object MainSettingsActionBinder {
    fun wire(
        activity: MainActivity,
        binding: ActivityMainBinding,
        openHome: () -> Unit,
        syncContacts: () -> Unit,
        saveServerSettings: () -> Unit,
        createArchive: () -> Unit,
        restoreArchive: () -> Unit,
        testStart: (() -> Unit)?,
        testEnd: (() -> Unit)?,
    ) {
        binding.backToHomeButton.setOnClickListener { openHome() }
        binding.contactLinkSection.registerAllContactsButton.setOnClickListener { syncContacts() }
        binding.remoteSettingsSection.saveServerSettingsButton.setOnClickListener { saveServerSettings() }
        binding.settingsRegistrationGroup.registrationServerAddressButton.setOnClickListener {
            binding.settingsMenuGroup.settingsServerButton.performClick()
        }
        binding.settingsRegistrationGroup.registrationCompanyAccountButton.setOnClickListener {
            RegistrationActions.openCompanyAccount(activity)
        }
        binding.settingsRegistrationGroup.registrationEditProfileButton.setOnClickListener {
            RegistrationActions.openProfileEditor(activity)
        }
        binding.settingsRegistrationGroup.registrationLogoutButton.setOnClickListener {
            RegistrationActions.logout(activity, binding.settingsRegistrationGroup)
        }
        binding.settingsRegistrationGroup.registrationJoinCompanyButton.setOnClickListener {
            RegistrationActions.showJoinDialog(activity)
        }
        binding.settingsRegistrationGroup.registrationRefreshCompaniesButton.setOnClickListener {
            RegistrationActions.renderCompanySection(activity, binding.settingsRegistrationGroup)
        }
        RegistrationActions.renderCompanySection(activity, binding.settingsRegistrationGroup)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                RegistrationActions.renderCompanySection(activity, binding.settingsRegistrationGroup)
            }
        })
        binding.archiveSettingsSection.createArchiveButton.setOnClickListener { createArchive() }
        binding.archiveSettingsSection.restoreArchiveButton.setOnClickListener { restoreArchive() }
        if (testStart != null && testEnd != null) {
            activity.findViewById<MaterialButton>(R.id.testStartPopupButton).setOnClickListener { testStart() }
            activity.findViewById<MaterialButton>(R.id.testEndPopupButton).setOnClickListener { testEnd() }
        }
    }
}
