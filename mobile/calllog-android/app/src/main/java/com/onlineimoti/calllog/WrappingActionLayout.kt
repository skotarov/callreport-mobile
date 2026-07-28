package com.onlineimoti.calllog

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Lays action buttons left-to-right and moves overflowing buttons to the next row. */
internal class WrappingActionLayout(context: Context) : ViewGroup(context) {
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
            val margins = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + margins.leftMargin + margins.rightMargin
            val childHeight = child.measuredHeight + margins.topMargin + margins.bottomMargin
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
            val margins = child.layoutParams as MarginLayoutParams
            val totalWidth = margins.leftMargin + child.measuredWidth + margins.rightMargin
            val totalHeight = margins.topMargin + child.measuredHeight + margins.bottomMargin

            if (x > paddingLeft && x + totalWidth > contentRight) {
                x = paddingLeft
                y += lineHeight + verticalSpacingPx
                lineHeight = 0
            }

            val childLeft = x + margins.leftMargin
            val childTop = y + margins.topMargin
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

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams = MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams = MarginLayoutParams(params)

    override fun checkLayoutParams(params: LayoutParams): Boolean = params is MarginLayoutParams
}
