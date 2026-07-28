package com.onlineimoti.calllog

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import kotlin.math.max

/** RadioGroup that keeps one checked item while laying choices left-to-right with row wrapping. */
internal class WrappingRadioGroup(context: Context) : RadioGroup(context) {
    var horizontalSpacingPx: Int = 0
    var verticalSpacingPx: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val contentLimit = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }

        var lineWidth = 0
        var lineHeight = 0
        var contentWidth = 0
        var contentHeight = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(
                child,
                widthMeasureSpec,
                paddingLeft + paddingRight,
                heightMeasureSpec,
                paddingTop + paddingBottom,
            )
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            val childWidth = child.measuredWidth + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0)
            val childHeight = child.measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
            val proposedWidth = if (lineWidth == 0) childWidth else lineWidth + horizontalSpacingPx + childWidth

            if (lineWidth > 0 && proposedWidth > contentLimit) {
                contentWidth = max(contentWidth, lineWidth)
                contentHeight += lineHeight + verticalSpacingPx
                lineWidth = childWidth
                lineHeight = childHeight
            } else {
                lineWidth = proposedWidth
                lineHeight = max(lineHeight, childHeight)
            }
        }

        if (lineWidth > 0) {
            contentWidth = max(contentWidth, lineWidth)
            contentHeight += lineHeight
        }

        setMeasuredDimension(
            resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentRight = right - left - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            val marginStart = margins?.leftMargin ?: 0
            val marginEnd = margins?.rightMargin ?: 0
            val marginTop = margins?.topMargin ?: 0
            val marginBottom = margins?.bottomMargin ?: 0
            val totalWidth = marginStart + child.measuredWidth + marginEnd
            val totalHeight = marginTop + child.measuredHeight + marginBottom

            if (x > paddingLeft && x + totalWidth > contentRight) {
                x = paddingLeft
                y += lineHeight + verticalSpacingPx
                lineHeight = 0
            }

            val childLeft = x + marginStart
            val childTop = y + marginTop
            child.layout(
                childLeft,
                childTop,
                childLeft + child.measuredWidth,
                childTop + child.measuredHeight,
            )
            x += totalWidth + horizontalSpacingPx
            lineHeight = max(lineHeight, totalHeight)
        }
    }
}
