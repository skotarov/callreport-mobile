package com.onlineimoti.calllog

internal fun MainActivity.saveServerSettings() {
    saveConfig()
    setStatus(getString(R.string.settings_server_saved))
    refreshServerDependentUi()
}

internal fun MainActivity.onRemoteEnabledRequested(enabled: Boolean) {
    if (suppressAutoSave) return
    if (enabled) {
        validateAndEnableServer()
        return
    }
    serverConnectionGeneration++
    applyTestedServerMode(MainSettingsConfigUi.read(binding), enabled = false)
    setStatus(getString(R.string.server_mode_disabled))
}

internal fun MainActivity.onRemoteConnectionInputChanged() {
    if (suppressAutoSave) return
    val wasEnabled = ConfigStore.load(this).remoteEnabled || binding.remoteSettingsSection.remoteEnabledCheckBox.isChecked
    serverConnectionGeneration++
    val entered = MainSettingsConfigUi.read(binding)
    ConfigStore.save(this, entered.copy(remoteEnabled = false))
    HomeCrmModeStore.setEnabled(this, false)
    setRemoteCheckbox(checked = false, enabled = true)
    if (wasEnabled) {
        setStatus(getString(R.string.server_connection_recheck_required))
        refreshServerDependentUi()
    }
}

internal fun MainActivity.validateAndEnableServer() {
    val generation = ++serverConnectionGeneration
    val entered = MainSettingsConfigUi.read(binding)
    ConfigStore.save(this, entered.copy(remoteEnabled = false))
    HomeCrmModeStore.setEnabled(this, false)
    val candidate = ConfigStore.load(this).copy(remoteEnabled = true)
    setRemoteCheckbox(checked = false, enabled = false)
    setStatus("⏳ ${getString(R.string.test_server_connection_running)}")
    executor.execute {
        val result = runCatching {
            val status = ServerConnectionTester.test(candidate)
            val session = if (status.ok) {
                CompanyAccountApi.refreshProfile(applicationContext).getOrThrow()
            } else {
                null
            }
            status to session
        }
        runOnUiThread {
            if (isFinishing || isDestroyed || generation != serverConnectionGeneration) return@runOnUiThread
            result.onSuccess { (status, session) ->
                val enabled = status.ok && session != null
                if (session != null) CompanyAccountApi.applySession(applicationContext, session)
                applyTestedServerMode(candidate, enabled)
                setStatus(buildString {
                    append(if (enabled) "✅ " else "⚠️ ")
                    append(status.title)
                    if (status.detail.isNotBlank()) append("\n").append(status.detail)
                })
            }.onFailure { error ->
                applyTestedServerMode(candidate, enabled = false)
                val message = error.message.orEmpty().ifBlank { getString(R.string.test_server_connection_failed) }
                setStatus("❌ ${getString(R.string.settings_registration_profile_load_failed, message)}")
            }
        }
    }
}

internal fun MainActivity.applyTestedServerMode(config: AppConfig, enabled: Boolean) {
    ConfigStore.save(this, config.copy(remoteEnabled = enabled))
    HomeCrmModeStore.setEnabled(this, enabled)
    setRemoteCheckbox(checked = enabled, enabled = true)
    refreshServerDependentUi()
}

internal fun MainActivity.setRemoteCheckbox(checked: Boolean, enabled: Boolean) {
    val previous = suppressAutoSave
    suppressAutoSave = true
    binding.remoteSettingsSection.remoteEnabledCheckBox.isChecked = checked
    binding.remoteSettingsSection.remoteEnabledCheckBox.isEnabled = enabled
    suppressAutoSave = previous
}

internal fun MainActivity.refreshServerDependentUi() {
    refreshPermissionSummary()
    serverSyncQueueStatusController.refresh()
    RegistrationActions.renderCompanySection(this, binding.settingsRegistrationGroup)
}
