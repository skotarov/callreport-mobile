package com.onlineimoti.calllog

import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService

internal class MainServerConnectionController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val executor: ExecutorService,
    private val setStatus: (String) -> Unit,
    private val refreshServerDependentUi: () -> Unit,
) {
    private var generation = 0
    private var suppressAutoSave = false

    val isAutoSaveSuppressed: Boolean
        get() = suppressAutoSave

    fun onRemoteEnabledRequested(enabled: Boolean) {
        if (suppressAutoSave) return
        if (enabled) {
            validateAndEnableServer()
            return
        }
        generation += 1
        applyTestedServerMode(MainSettingsConfigUi.read(binding), enabled = false)
        setStatus(activity.getString(R.string.server_mode_disabled))
    }

    fun onRemoteConnectionInputChanged() {
        if (suppressAutoSave) return
        val wasEnabled = ConfigStore.load(activity).remoteEnabled ||
            binding.remoteSettingsSection.remoteEnabledCheckBox.isChecked
        generation += 1
        val entered = MainSettingsConfigUi.read(binding)
        ConfigStore.save(activity, entered.copy(remoteEnabled = false))
        HomeCrmModeStore.setEnabled(activity, false)
        setRemoteCheckbox(checked = false, enabled = true)
        if (wasEnabled) {
            setStatus(activity.getString(R.string.server_connection_recheck_required))
            refreshServerDependentUi()
        }
    }

    private fun validateAndEnableServer() {
        val requestGeneration = ++generation
        val entered = MainSettingsConfigUi.read(binding)
        ConfigStore.save(activity, entered.copy(remoteEnabled = false))
        HomeCrmModeStore.setEnabled(activity, false)
        val candidate = ConfigStore.load(activity).copy(remoteEnabled = true)
        setRemoteCheckbox(checked = false, enabled = false)
        setStatus("⏳ ${activity.getString(R.string.test_server_connection_running)}")
        executor.execute {
            val result = runCatching {
                val status = ServerConnectionTester.test(candidate)
                val session = if (status.ok) {
                    CompanyAccountApi.refreshProfile(activity.applicationContext).getOrThrow()
                } else {
                    null
                }
                status to session
            }
            activity.runOnUiThread {
                if (
                    activity.isFinishing || activity.isDestroyed ||
                    requestGeneration != generation
                ) return@runOnUiThread
                result.onSuccess { (status, session) ->
                    val enabled = status.ok && session != null
                    if (session != null) {
                        CompanyAccountApi.applySession(activity.applicationContext, session)
                    }
                    applyTestedServerMode(candidate, enabled)
                    setStatus(buildString {
                        append(if (enabled) "✅ " else "⚠️ ")
                        append(status.title)
                        if (status.detail.isNotBlank()) append("\n").append(status.detail)
                    })
                }.onFailure { error ->
                    applyTestedServerMode(candidate, enabled = false)
                    val message = error.message.orEmpty().ifBlank {
                        activity.getString(R.string.test_server_connection_failed)
                    }
                    setStatus(
                        "❌ ${activity.getString(R.string.settings_registration_profile_load_failed, message)}",
                    )
                }
            }
        }
    }

    private fun applyTestedServerMode(config: AppConfig, enabled: Boolean) {
        ConfigStore.save(activity, config.copy(remoteEnabled = enabled))
        HomeCrmModeStore.setEnabled(activity, enabled)
        setRemoteCheckbox(checked = enabled, enabled = true)
        refreshServerDependentUi()
    }

    private fun setRemoteCheckbox(checked: Boolean, enabled: Boolean) {
        val previous = suppressAutoSave
        suppressAutoSave = true
        binding.remoteSettingsSection.remoteEnabledCheckBox.isChecked = checked
        binding.remoteSettingsSection.remoteEnabledCheckBox.isEnabled = enabled
        suppressAutoSave = previous
    }
}
