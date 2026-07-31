package com.onlineimoti.calllog

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import com.google.android.material.button.MaterialButton

/** Places the manual server/token entry point inside the signed-in profile screen. */
internal object ProfileServerControlsUi {
    private const val MANUAL_SERVER_TAG = "relationship_manager_profile_manual_server"

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is ProfileEditorActivity) return
                activity.window.decorView.post { install(activity) }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun install(activity: ProfileEditorActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (findTaggedView(content, MANUAL_SERVER_TAG) != null) return
        val logout = findButtonByText(
            content,
            activity.getString(R.string.settings_registration_logout),
        ) ?: return
        val column = logout.parent as? LinearLayout ?: return
        val originalIndex = column.indexOfChild(logout)
        if (originalIndex < 0) return
        column.removeView(logout)

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val logoutRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        logoutRow.addView(
            Space(activity),
            LinearLayout.LayoutParams(0, 1, 0.5f),
        )
        logoutRow.addView(
            logout,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        logoutRow.addView(
            Space(activity),
            LinearLayout.LayoutParams(0, 1, 0.5f),
        )
        column.addView(
            logoutRow,
            originalIndex,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) },
        )

        val manualServerButton = MaterialButton(
            activity,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            tag = MANUAL_SERVER_TAG
            setText(R.string.settings_registration_server_address)
            isAllCaps = false
            setOnClickListener {
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_OPEN_SERVER, true)
                })
                activity.finish()
            }
        }
        column.addView(
            manualServerButton,
            originalIndex + 1,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
    }

    private fun findButtonByText(group: ViewGroup, text: String): MaterialButton? {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is MaterialButton && child.text?.toString() == text) return child
            if (child is ViewGroup) findButtonByText(child, text)?.let { return it }
        }
        return null
    }

    private fun findTaggedView(group: ViewGroup, tag: String): View? {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.tag == tag) return child
            if (child is ViewGroup) findTaggedView(child, tag)?.let { return it }
        }
        return null
    }
}
