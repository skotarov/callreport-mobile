package com.onlineimoti.calllog

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.util.WeakHashMap

/** Keeps the indeterminate Home loader as the final row inside the scrollable list. */
internal object HomeLoadingFooterUi {
    private const val FOOTER_TAG = "relationship_manager_home_loading_footer"
    private val footers = WeakHashMap<LinearLayout, LinearLayout>()

    fun show(container: LinearLayout) {
        // PullToRefreshLayout already presents the active refresh animation. Showing
        // the list footer at the same time creates two competing loading indicators.
        if (isPullRefreshing(container)) {
            footers[container]?.visibility = View.INVISIBLE
            return
        }
        footer(container).apply {
            visibility = View.VISIBLE
            moveToEnd(container, this)
        }
    }

    fun hide(container: LinearLayout) {
        footer(container).apply {
            visibility = View.INVISIBLE
            moveToEnd(container, this)
        }
        // Rows/status are now visible and usable. Names, local notes and server
        // notes may continue enriching them without keeping the main calls loader.
        HomePageReadyState.markRowsVisible()
    }

    fun isFooter(view: View?): Boolean = view?.tag == FOOTER_TAG

    fun hasRows(container: LinearLayout): Boolean = (0 until container.childCount)
        .any { index -> !isFooter(container.getChildAt(index)) }

    fun keepLast(container: LinearLayout) {
        footers[container]?.let { footer -> moveToEnd(container, footer) }
    }

    private fun footer(container: LinearLayout): LinearLayout {
        footers[container]?.let { return it }
        val density = container.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return LinearLayout(container.context).apply {
            tag = FOOTER_TAG
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            )
            addView(
                ProgressBar(container.context, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                },
            )
        }.also { created ->
            footers[container] = created
            container.addView(created)
        }
    }

    private fun isPullRefreshing(container: LinearLayout): Boolean {
        var current: View? = container
        while (current != null) {
            if (current is PullToRefreshLayout) return current.isRefreshing
            current = current.parent as? View
        }
        return false
    }

    private fun moveToEnd(container: LinearLayout, footer: LinearLayout) {
        if (footer.parent !== container) {
            (footer.parent as? ViewGroup)?.removeView(footer)
            container.addView(footer)
            return
        }
        if (container.indexOfChild(footer) == container.childCount - 1) return
        container.removeView(footer)
        container.addView(footer)
    }
}
