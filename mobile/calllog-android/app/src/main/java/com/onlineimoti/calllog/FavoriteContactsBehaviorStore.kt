package com.onlineimoti.calllog

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatCheckBox

/** Local-only behavior switch for favorite contacts in the Home overflow menu. */
internal object FavoriteContactsBehaviorStore {
    private const val PREFS = "favorite_contacts_behavior"
    private const val KEY_DIRECT_DIAL = "direct_dial"

    fun directDialEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DIRECT_DIAL, false)

    fun setDirectDialEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DIRECT_DIAL, enabled)
            .apply()
    }
}

/** Self-persisting Settings checkbox so this isolated behavior does not broaden AppConfig. */
internal class FavoriteContactsDirectDialCheckBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.checkboxStyle,
) : AppCompatCheckBox(context, attrs, defStyleAttr) {
    init {
        isChecked = FavoriteContactsBehaviorStore.directDialEnabled(context)
        setOnCheckedChangeListener { _, isChecked ->
            FavoriteContactsBehaviorStore.setDirectDialEnabled(context, isChecked)
        }
    }
}
