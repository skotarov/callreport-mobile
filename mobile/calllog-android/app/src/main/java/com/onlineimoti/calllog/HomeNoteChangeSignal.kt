package com.onlineimoti.calllog

import android.content.Context

/**
 * Durable process-independent note revision.
 *
 * Home is paused while History or the note editor is on top, so its dynamic
 * BroadcastReceiver can miss the save broadcast. The revision lets Home detect
 * that missed change as soon as it resumes.
 */
internal object HomeNoteChangeSignal {
    private const val PREFS = "relationship_manager_home_note_change_signal"
    private const val KEY_REVISION = "revision"
    private val lock = Any()

    fun current(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(KEY_REVISION, 0L)

    fun markChanged(context: Context): Long = synchronized(lock) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getLong(KEY_REVISION, 0L)
        val next = maxOf(previous + 1L, System.currentTimeMillis())
        prefs.edit().putLong(KEY_REVISION, next).commit()
        next
    }
}
