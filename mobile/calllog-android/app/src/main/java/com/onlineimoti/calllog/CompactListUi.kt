package com.onlineimoti.calllog

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.google.android.material.card.MaterialCardView
import java.util.Collections
import java.util.WeakHashMap

/** Compact visual grouping shared by Call Log, History, SMS and CRM lists. */
internal object ListThemeUi {
    private val groupedRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val pendingParents = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val originalRadii = WeakHashMap<View, Float>()

    fun applyRowSpacing(view: View, dp: (Int) -> Int): View {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return view
        rememberRadius(view, dp)
        groupedRows.add(view)
        params.topMargin = 0
        params.bottomMargin = 0
        view.layoutParams = params
        scheduleGroupStyle(view, dp)
        return view
    }

    private fun scheduleGroupStyle(view: View, dp: (Int) -> Int) {
        val parent = view.parent as? ViewGroup
        if (parent != null) {
            scheduleParentPreDraw(parent, dp)
            return
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attachedView: View) {
                attachedView.removeOnAttachStateChangeListener(this)
                (attachedView.parent as? ViewGroup)?.let { scheduleParentPreDraw(it, dp) }
            }

            override fun onViewDetachedFromWindow(detachedView: View) = Unit
        })
    }

    private fun scheduleParentPreDraw(parent: ViewGroup, dp: (Int) -> Int) {
        if (!pendingParents.add(parent)) return
        val observer = parent.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                else if (parent.viewTreeObserver.isAlive) parent.viewTreeObserver.removeOnPreDrawListener(this)
                pendingParents.remove(parent)
                val changed = styleGroups(parent, dp)
                if (changed) parent.requestLayout()
                return !changed
            }
        })
    }

    /** Date and week headers are not registered rows, so they split groups. */
    private fun styleGroups(parent: ViewGroup, dp: (Int) -> Int): Boolean {
        var changed = false
        val group = mutableListOf<View>()
        fun flush() {
            if (group.isEmpty()) return
            group.forEachIndexed { index, row ->
                val first = index == 0
                val last = index == group.lastIndex
                (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    val wantedTop = if (first) 0 else -dp(1)
                    if (params.topMargin != wantedTop || params.bottomMargin != 0) {
                        params.topMargin = wantedTop
                        params.bottomMargin = 0
                        row.layoutParams = params
                        changed = true
                    }
                }
                applyCorners(row, top = first, bottom = last, dp = dp)
            }
            group.clear()
        }

        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (groupedRows.contains(child)) group.add(child) else flush()
        }
        flush()
        return changed
    }

    private fun rememberRadius(view: View, dp: (Int) -> Int) {
        if (originalRadii.containsKey(view)) return
        val radius = when (view) {
            is MaterialCardView -> view.radius
            else -> (view.background as? GradientDrawable)?.cornerRadius ?: 0f
        }.takeIf { it > 0f } ?: dp(12).toFloat()
        originalRadii[view] = radius
    }

    private fun applyCorners(view: View, top: Boolean, bottom: Boolean, dp: (Int) -> Int) {
        val radius = originalRadii[view] ?: dp(12).toFloat()
        val topRadius = if (top) radius else 0f
        val bottomRadius = if (bottom) radius else 0f
        when (view) {
            is MaterialCardView -> {
                view.shapeAppearanceModel = view.shapeAppearanceModel.toBuilder()
                    .setTopLeftCornerSize(topRadius)
                    .setTopRightCornerSize(topRadius)
                    .setBottomRightCornerSize(bottomRadius)
                    .setBottomLeftCornerSize(bottomRadius)
                    .build()
            }
            else -> {
                val background = view.background?.mutate() as? GradientDrawable ?: return
                background.cornerRadii = floatArrayOf(
                    topRadius, topRadius, topRadius, topRadius,
                    bottomRadius, bottomRadius, bottomRadius, bottomRadius,
                )
                view.background = background
            }
        }
    }
}
