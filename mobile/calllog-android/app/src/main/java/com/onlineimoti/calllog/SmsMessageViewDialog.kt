package com.onlineimoti.calllog

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Modal viewer for one complete SMS, opened from notifications or History rows. */
internal class SmsMessageViewDialog(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun show(
        phone: String,
        title: String,
        body: String,
        receivedAtMs: Long,
        direction: String = "sms_in",
        showReplyAction: Boolean = true,
        showCloseAction: Boolean = false,
        onDismiss: (() -> Unit)? = null,
    ) {
        if (phone.isBlank() || activity.isFinishing || activity.isDestroyed) {
            onDismiss?.invoke()
            return
        }
        runCatching {
            var openingAnotherUi = false
            val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
            dialog.setContentView(
                content(
                    dialog = dialog,
                    phone = phone,
                    title = title.ifBlank { phone },
                    body = body,
                    receivedAtMs = receivedAtMs,
                    direction = direction,
                    showReplyAction = showReplyAction,
                    showCloseAction = showCloseAction,
                    openReply = {
                        openingAnotherUi = true
                        dialog.dismiss()
                        SmsComposeDialog(activity, dp).show(
                            phone = phone,
                            title = title.ifBlank { phone },
                            onDismiss = onDismiss,
                        )
                    },
                ),
            )
            dialog.setOnShowListener { configureWindow(dialog) }
            dialog.setOnDismissListener { if (!openingAnotherUi) onDismiss?.invoke() }
            dialog.show()
        }.onFailure { error ->
            Toast.makeText(
                activity,
                error.message.orEmpty().ifBlank {
                    if (AppLocaleText.isBulgarian()) "Не успях да отворя SMS." else "Could not open SMS."
                },
                Toast.LENGTH_LONG,
            ).show()
            onDismiss?.invoke()
        }
    }

    private fun configureWindow(dialog: Dialog) {
        AppModalStyle.configureWindow(dialog, activity, topAligned = true)
    }

    private fun content(
        dialog: Dialog,
        phone: String,
        title: String,
        body: String,
        receivedAtMs: Long,
        direction: String,
        showReplyAction: Boolean,
        showCloseAction: Boolean,
        openReply: () -> Unit,
    ): LinearLayout {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = AppModalStyle.surface(activity)
        }
        root.addView(header(dialog))
        root.addView(TextView(activity).apply {
            text = listOf(
                title.takeIf { it != phone },
                phone,
                SmsMessageDetailPolicy.directionLabel(direction),
                PhoneCallReader.formatStartedAt(receivedAtMs),
            ).filter { !it.isNullOrBlank() }.joinToString(" • ")
            textSize = 13.5f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(dp(12), dp(9), dp(12), dp(10))
            background = AppModalStyle.secondary(activity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        })

        val bodyView = TextView(activity).apply {
            text = body.ifBlank {
                if (AppLocaleText.isBulgarian()) "Празно SMS" else "Empty SMS"
            }
            textSize = 16f
            setTextColor(Color.rgb(15, 23, 42))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = AppModalStyle.input(activity)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(
            MaxHeightScrollView(
                context = activity,
                maxHeightPx = (activity.resources.displayMetrics.heightPixels * 0.56f).toInt(),
            ).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(bodyView)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        if (showCloseAction || showReplyAction) {
            root.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48),
                ).apply { topMargin = dp(16) }

                if (showCloseAction) {
                    addView(
                        actionButton(
                            label = if (AppLocaleText.isBulgarian()) "Затвори" else "Close",
                            primary = false,
                            onClick = { dialog.dismiss() },
                        ),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                            if (showReplyAction) marginEnd = dp(8)
                        },
                    )
                }
                if (showReplyAction) {
                    val replyLabel = when {
                        SmsMessageDetailPolicy.isOutgoing(direction) && AppLocaleText.isBulgarian() -> "Ново SMS"
                        SmsMessageDetailPolicy.isOutgoing(direction) -> "New SMS"
                        AppLocaleText.isBulgarian() -> "Отговори"
                        else -> "Reply"
                    }
                    addView(
                        actionButton(replyLabel, primary = true, onClick = openReply),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                    )
                }
            })
        }
        return root
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit): Button {
        return Button(activity).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else Color.rgb(15, 23, 42))
            background = roundedRect(
                color = if (primary) AppModalStyle.accent(activity) else Color.rgb(248, 250, 252),
                radius = dp(13),
                strokeColor = if (primary) Color.TRANSPARENT else Color.rgb(203, 213, 225),
                strokeWidth = if (primary) 0 else dp(1),
            )
            setOnClickListener { onClick() }
        }
    }

    private fun header(dialog: Dialog): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(headerIcon(), LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginEnd = dp(12)
        })
        addView(TextView(activity).apply {
            text = "SMS"
            textSize = 20f
            setTextColor(Color.rgb(15, 23, 42))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(activity).apply {
            text = "×"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(71, 85, 105))
            background = AppModalStyle.secondary(activity)
            contentDescription = if (AppLocaleText.isBulgarian()) "Затвори" else "Close"
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        })
    }

    private fun headerIcon(): FrameLayout = FrameLayout(activity).apply {
        background = roundedRect(AppModalStyle.accent(activity), dp(14), Color.TRANSPARENT, 0)
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_menu_sms)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private class MaxHeightScrollView(
        context: Context,
        private val maxHeightPx: Int,
    ) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val originalMode = MeasureSpec.getMode(heightMeasureSpec)
            val originalSize = MeasureSpec.getSize(heightMeasureSpec)
            val cappedSize = if (originalMode == MeasureSpec.UNSPECIFIED) {
                maxHeightPx
            } else {
                minOf(originalSize, maxHeightPx)
            }
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(cappedSize, MeasureSpec.AT_MOST),
            )
        }
    }
}
