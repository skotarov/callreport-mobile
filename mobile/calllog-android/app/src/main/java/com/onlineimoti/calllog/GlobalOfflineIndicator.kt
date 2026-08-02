package com.onlineimoti.calllog

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap

/**
 * Shows a small fixed no-internet icon above every app Activity while a remote
 * Relationship Manager account is in use. The indicator never changes account,
 * CRM, note, or synchronization rules; it only reflects validated connectivity.
 */
internal object GlobalOfflineIndicator {
    private const val INDICATOR_SIZE_DP = 34
    private const val INDICATOR_TOP_MARGIN_DP = 6
    private const val ACCOUNT_STATE_REFRESH_MS = 1_000L

    private data class InstalledIndicator(
        val decor: ViewGroup,
        val view: ImageView,
        val layoutListener: View.OnLayoutChangeListener,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resumedActivities = linkedSetOf<Activity>()
    private val indicators = WeakHashMap<Activity, InstalledIndicator>()
    private val accountStateRefresh = object : Runnable {
        override fun run() {
            if (resumedActivities.isEmpty()) return
            resumedActivities.toList().forEach(::update)
            mainHandler.postDelayed(this, ACCOUNT_STATE_REFRESH_MS)
        }
    }

    private var registered = false
    private var internetValidated = true
    private lateinit var connectivityManager: ConnectivityManager

    fun register(app: Application) {
        if (registered) return
        registered = true
        connectivityManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        internetValidated = hasValidatedInternet()

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                val wasEmpty = resumedActivities.isEmpty()
                resumedActivities += activity
                activity.window.decorView.post { update(activity) }
                if (wasEmpty) {
                    mainHandler.removeCallbacks(accountStateRefresh)
                    mainHandler.postDelayed(accountStateRefresh, ACCOUNT_STATE_REFRESH_MS)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities -= activity
                if (resumedActivities.isEmpty()) mainHandler.removeCallbacks(accountStateRefresh)
            }

            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                resumedActivities -= activity
                if (resumedActivities.isEmpty()) mainHandler.removeCallbacks(accountStateRefresh)
                remove(activity)
            }
        })

        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshConnectivity()
            override fun onLost(network: Network) = refreshConnectivity()

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                publishConnectivity(capabilities.hasValidatedInternet())
            }
        })
    }

    private fun refreshConnectivity() {
        publishConnectivity(hasValidatedInternet())
    }

    private fun publishConnectivity(validated: Boolean) {
        if (internetValidated == validated) return
        internetValidated = validated
        mainHandler.post {
            resumedActivities.toList().forEach(::update)
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(network)?.hasValidatedInternet() == true
    }

    private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun update(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val installed = ensureInstalled(activity) ?: return
        val visible = remoteAccountEnabled(activity) && !internetValidated
        installed.view.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) layoutIndicator(activity, installed)
    }

    /**
     * A configured manual server or a locally remembered signed-in profile means
     * remote account features are active. Local-only use does not show the icon.
     */
    private fun remoteAccountEnabled(context: Context): Boolean {
        val config = ConfigStore.load(context.applicationContext)
        if (CallReportRemoteAccess.isReady(config)) return true
        return CompanySessionStore.loadStored(context.applicationContext)?.profileReady == true
    }

    private fun ensureInstalled(activity: Activity): InstalledIndicator? {
        indicators[activity]?.let { return it }
        val decor = activity.window.decorView as? ViewGroup ?: return null
        val view = ImageView(activity).apply {
            contentDescription = "Няма интернет"
            setImageResource(R.drawable.ic_no_internet)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(activity, 7), dp(activity, 7), dp(activity, 7), dp(activity, 7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(211, 47, 47))
            }
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            indicators[activity]?.let { installed -> layoutIndicator(activity, installed) }
        }
        val installed = InstalledIndicator(decor, view, listener)
        indicators[activity] = installed
        decor.overlay.add(view)
        decor.addOnLayoutChangeListener(listener)
        layoutIndicator(activity, installed)
        return installed
    }

    private fun layoutIndicator(activity: Activity, installed: InstalledIndicator) {
        val decor = installed.decor
        if (decor.width <= 0 || decor.height <= 0) {
            decor.post { indicators[activity]?.let { layoutIndicator(activity, it) } }
            return
        }
        val size = dp(activity, INDICATOR_SIZE_DP)
        val statusBarTop = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())
            ?.top
            ?: 0
        val left = ((decor.width - size) / 2).coerceAtLeast(0)
        val top = statusBarTop + dp(activity, INDICATOR_TOP_MARGIN_DP)
        installed.view.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        )
        installed.view.layout(left, top, left + size, top + size)
    }

    private fun remove(activity: Activity) {
        val installed = indicators.remove(activity) ?: return
        installed.decor.removeOnLayoutChangeListener(installed.layoutListener)
        installed.decor.overlay.remove(installed.view)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
