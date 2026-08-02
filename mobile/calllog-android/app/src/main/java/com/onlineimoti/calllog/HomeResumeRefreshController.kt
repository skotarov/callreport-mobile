package com.onlineimoti.calllog

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/**
 * Rechecks an already rendered Call Log after returning from another screen.
 * The initial page load is deliberately excluded so it is never parsed twice.
 */
internal class HomeResumeRefreshController private constructor(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var firstResume = true
    private var settingsWasOpened = false
    private var receiverRegistered = false

    private val refreshRunnable = Runnable {
        if (!canRefreshLoadedPage() || !activity.hasWindowFocus()) return@Runnable
        // This is a recheck of an already visible page, not a first load. Keep the
        // existing rows on screen while the fresh snapshot is prepared.
        HomeRefreshRenderPolicy.requestKeepExistingRows()
        requestAuthoritativeRefresh()
    }

    private val reloadAfterSettingsRunnable = Runnable {
        if (activity.isFinishing || activity.isDestroyed) return@Runnable
        if (HomePagedListUi.visiblePageCount(binding.homeCallsContainer) > 0) return@Runnable

        // The call model may still contain rows even though Android has lost the
        // rendered page. Force one clean rebuild from the authoritative loaders.
        HomeRefreshRenderPolicy.requestForceRebuild()
        HomeBusyTooltipUi.clear(activity)
        HomeLoadingFooterUi.show(binding.homeCallsContainer)
        requestAuthoritativeRefresh()
    }

    private val dataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A real save already causes an immediate authoritative refresh.
            // Do not repeat it again five seconds later.
            cancelPending()
        }
    }

    private fun install() {
        activity.application.registerActivityLifecycleCallbacks(this)
        val filter = IntentFilter().apply {
            addAction(HomeActivity.ACTION_CONTACT_NOTE_SAVED)
            addAction(PostCallOverlayService.ACTION_NOTES_CHANGED)
        }
        ContextCompat.registerReceiver(
            activity,
            dataChangedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    override fun onActivityResumed(resumedActivity: Activity) {
        if (resumedActivity is MainActivity) {
            settingsWasOpened = true
            // Several settings may save in succession. Keep every intermediate
            // refresh from clearing the already visible Call Log.
            HomeRefreshRenderPolicy.holdExistingRows()
            return
        }
        if (resumedActivity !== activity) return
        cancelPending()
        if (firstResume) {
            firstResume = false
            return
        }
        if (settingsWasOpened) {
            settingsWasOpened = false
            HomeRefreshRenderPolicy.releaseHeldRows()
            if (HomePagedListUi.visiblePageCount(binding.homeCallsContainer) > 0) {
                HomeRefreshRenderPolicy.clear()
            } else {
                // Do not leave Home on an empty container with a permanent
                // “loading calls” state after returning from Settings.
                handler.post(reloadAfterSettingsRunnable)
            }
            return
        }
        if (HomePagedListUi.visiblePageCount(binding.homeCallsContainer) == 0) {
            // Also recover when Android removed the page while another app was in
            // front. The user should never need to switch to Clients and back.
            handler.post(reloadAfterSettingsRunnable)
        } else if (canRefreshLoadedPage()) {
            handler.postDelayed(refreshRunnable, RESUME_REFRESH_DELAY_MS)
        }
    }

    override fun onActivityPaused(pausedActivity: Activity) {
        if (pausedActivity === activity) cancelPending()
    }

    override fun onActivityDestroyed(destroyedActivity: Activity) {
        if (destroyedActivity !== activity) return
        release()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun canRefreshLoadedPage(): Boolean {
        return !activity.isFinishing &&
            !activity.isDestroyed &&
            HomePageReadyState.isReady() &&
            HomePagedListUi.visiblePageCount(binding.homeCallsContainer) > 0 &&
            !binding.homeCallsRefreshLayout.isRefreshing &&
            binding.searchRow.visibility != View.VISIBLE &&
            !HomeCrmTimelineModeToggle.isContactsMode() &&
            !HomeCrmModeStore.isEnabled(activity)
    }

    private fun requestAuthoritativeRefresh() {
        activity.sendBroadcast(
            Intent(HomeActivity.ACTION_CONTACT_NOTE_SAVED)
                .setPackage(activity.packageName),
        )
    }

    private fun cancelPending() {
        handler.removeCallbacks(refreshRunnable)
        handler.removeCallbacks(reloadAfterSettingsRunnable)
    }

    private fun release() {
        cancelPending()
        HomeRefreshRenderPolicy.clear()
        activity.application.unregisterActivityLifecycleCallbacks(this)
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(dataChangedReceiver) }
            receiverRegistered = false
        }
    }

    companion object {
        private const val RESUME_REFRESH_DELAY_MS = 5_000L

        fun install(activity: AppCompatActivity, binding: ActivityHomeBinding) {
            HomeResumeRefreshController(activity, binding).install()
        }
    }
}
