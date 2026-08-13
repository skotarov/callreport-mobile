package com.onlineimoti.calllog

import android.view.View
import androidx.appcompat.app.AppCompatActivity

/** Keeps HomeActivity focused on state coordination rather than menu plumbing. */
internal object HomeOverflowMenu {
    fun show(activity: AppCompatActivity, anchor: View, openSettings: () -> Unit) {
        HomeOverflowCustomMenu.show(activity, anchor, openSettings)
    }
}
