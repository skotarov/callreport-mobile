package com.onlineimoti.calllog

import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService

internal object MainTestActions {
    @Suppress("UNUSED_PARAMETER")
    fun testStartPopup(
        activity: AppCompatActivity,
        binding: ActivityMainBinding,
        executor: ExecutorService,
        setStatus: (String) -> Unit,
    ) {
        val phone = binding.testsSection.phoneInput.text?.toString().orEmpty().ifBlank { "0877904903" }
        val direction = selectedDirection(binding)
        val baseConfig = ConfigStore.load(activity)
        // The Settings button is a visual/functional test, so contact filters must not
        // suppress it. Everything after that uses the exact real incoming-call flow.
        val testConfig = baseConfig.copy(
            notifyKnownContacts = true,
            notifyUnknownContacts = true,
            contactGroups = "",
        )
        IncomingCallLookupCoordinator(
            context = activity,
            config = testConfig,
            phone = phone,
            direction = direction,
            fullscreen = false,
            onLookupFinished = {
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val mode = activity.getString(
                        if (
                            baseConfig.useOverlayPopups &&
                            baseConfig.useCustomStartPopup &&
                            Settings.canDrawOverlays(activity)
                        ) {
                            R.string.test_mode_custom_overlay
                        } else {
                            R.string.test_mode_system_notification
                        },
                    )
                    setStatus(activity.getString(R.string.test_start_status, mode, phone))
                }
            },
        ).start()
    }

    fun testEndPopup(
        activity: AppCompatActivity,
        binding: ActivityMainBinding,
        executor: ExecutorService,
        setStatus: (String) -> Unit,
    ) {
        val phone = binding.testsSection.phoneInput.text?.toString().orEmpty().ifBlank { "0877904903" }
        val direction = selectedDirection(binding)
        executor.execute {
            val title = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty()
                .ifBlank { activity.getString(R.string.test_end_title) }
            activity.runOnUiThread {
                val config = ConfigStore.load(activity)
                if (config.postCallEndAction == ConfigStore.POST_CALL_END_ACTION_NOTHING) {
                    setStatus(activity.getString(R.string.test_end_disabled))
                    return@runOnUiThread
                }
                CallReportRuntime.showPostCallPromptNotification(
                    context = activity,
                    formUrl = "",
                    phone = phone,
                    direction = direction,
                    title = title,
                )
                val mode = activity.getString(
                    when {
                        config.useOverlayPopups && config.useCustomEndPopup && Settings.canDrawOverlays(activity) -> R.string.test_mode_custom_overlay
                        config.postCallEndAction == ConfigStore.POST_CALL_END_ACTION_HISTORY -> R.string.test_mode_history
                        else -> R.string.test_mode_note_editor
                    },
                )
                setStatus(activity.getString(R.string.test_end_status, mode, phone))
            }
        }
    }

    private fun selectedDirection(binding: ActivityMainBinding): String {
        return if (binding.testsSection.directionIn.isChecked) "in" else "out"
    }
}
