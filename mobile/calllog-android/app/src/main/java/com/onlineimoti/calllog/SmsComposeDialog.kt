package com.onlineimoti.calllog

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Small in-place SMS composer used from the contact-history header. */
internal class SmsComposeDialog(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val subscriptionChooser by lazy { SmsSubscriptionChooser(activity, dp, ::roundedRect) }

    /** The optional callback is used only by transparent notification launchers. */
    fun show(phone: String, title: String, initialBody: String = "", onDismiss: (() -> Unit)? = null) {
        if (phone.isBlank() || activity.isFinishing || activity.isDestroyed) {
            onDismiss?.invoke()
            return
        }
        runCatching {
            val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
            val views = content(dialog, phone, title, initialBody)
            dialog.setContentView(views.root)
            dialog.setOnShowListener { configureWindow(dialog, views.messageInput) }
            dialog.setOnDismissListener { onDismiss?.invoke() }
            dialog.show()
        }.onFailure { error ->
            Toast.makeText(
                activity,
                error.message.orEmpty().ifBlank { activity.getString(R.string.dynamic_sms_open_failed) },
                Toast.LENGTH_LONG,
            ).show()
            onDismiss?.invoke()
        }
    }

    private fun configureWindow(dialog: Dialog, input: EditText) {
        AppModalStyle.configureWindow(
            dialog = dialog,
            activity = activity,
            topAligned = true,
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        input.requestFocus()
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun content(dialog: Dialog, phone: String, title: String, initialBody: String): DialogViews {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = AppModalStyle.surface(activity)
        }
        root.addView(dialogTitle(dialog))
        root.addView(contactSummary(title.trim().ifBlank { phone }, phone))
        val selectedSubscriptionId = subscriptionChooser.addTo(root)
        val messageInput = messageInput(initialBody)
        val status = statusText()
        val historyButton = secondaryButton(activity.getString(R.string.dynamic_sms_history))
        val sendButton = primaryButton(activity.getString(R.string.dynamic_sms_send))
        root.addView(messageInput)
        root.addView(status)
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(historyButton, LinearLayout.LayoutParams(0, dp(48), 0.88f).apply {
                marginEnd = dp(10)
            })
            addView(sendButton, LinearLayout.LayoutParams(0, dp(52), 1.12f))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16) })
        historyButton.setOnClickListener {
            activity.startActivity(
                Intent(activity, SmsHistoryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            dialog.dismiss()
        }
        sendButton.setOnClickListener {
            send(
                dialog = dialog,
                phone = phone,
                message = messageInput.text?.toString().orEmpty(),
                subscriptionId = selectedSubscriptionId(),
                sendButton = sendButton,
                status = status,
            )
        }
        return DialogViews(root, messageInput)
    }

    private fun dialogTitle(dialog: Dialog): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(headerIcon(R.drawable.ic_sms_send), LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginEnd = dp(12)
        })
        addView(TextView(activity).apply {
            text = activity.getString(R.string.dynamic_sms_new)
            textSize = 20f
            setTextColor(Color.rgb(15, 23, 42))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(activity).apply {
            text = "×"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(71, 85, 105))
            background = AppModalStyle.secondary(activity)
            contentDescription = activity.getString(R.string.dynamic_sms_close)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        })
    }

    private fun contactSummary(title: String, phone: String): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(10))
        background = roundedRect(Color.rgb(248, 250, 252), dp(14), Color.rgb(226, 232, 240), dp(1))
        addView(TextView(activity).apply {
            text = title
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 23, 42))
        })
        addView(TextView(activity).apply {
            text = phone
            textSize = 13.5f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, dp(2), 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(14) }
    }

    private fun headerIcon(drawableRes: Int): FrameLayout = FrameLayout(activity).apply {
        background = roundedRect(AppModalStyle.accent(activity), dp(14), Color.TRANSPARENT, 0)
        addView(ImageView(activity).apply {
            setImageResource(drawableRes)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun messageInput(initialBody: String): EditText = EditText(activity).apply {
        hint = activity.getString(R.string.dynamic_sms_message_hint)
        setText(initialBody)
        setSelection(text?.length ?: 0)
        textSize = 16f
        minLines = 4
        maxLines = 8
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = AppModalStyle.input(activity)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun statusText(): TextView = TextView(activity).apply {
        textSize = 13f
        setTextColor(Color.rgb(185, 28, 28))
        visibility = View.GONE
        setPadding(0, dp(8), 0, 0)
    }

    private fun send(
        dialog: Dialog,
        phone: String,
        message: String,
        subscriptionId: Int?,
        sendButton: Button,
        status: TextView,
    ) {
        sendButton.isEnabled = false
        sendButton.text = activity.getString(R.string.dynamic_sms_sending)
        status.visibility = View.GONE
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val result = runCatching {
                SmsMessageSender.send(activity.applicationContext, phone, message, subscriptionId)
            }.getOrElse { Result.failure(it) }
            mainHandler.post {
                executor.shutdown()
                if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@post
                result.onSuccess { outcome ->
                    subscriptionChooser.rememberSuccessfulSubscriptionId(subscriptionId)
                    activity.sendBroadcast(
                        Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(activity.packageName),
                    )
                    Toast.makeText(
                        activity,
                        activity.getString(if (outcome.historySaved) R.string.dynamic_sms_sent else R.string.dynamic_sms_sent_refresh),
                        Toast.LENGTH_LONG,
                    ).show()
                    dialog.dismiss()
                }.onFailure { restoreAfterFailure(sendButton, status, it) }
            }
        }
    }

    private fun restoreAfterFailure(sendButton: Button, status: TextView, error: Throwable) {
        sendButton.isEnabled = true
        sendButton.text = activity.getString(R.string.dynamic_sms_send)
        status.text = error.message.orEmpty().ifBlank { activity.getString(R.string.dynamic_sms_send_failed) }
        status.visibility = View.VISIBLE
    }

    private fun primaryButton(label: String): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPaddingRelative(dp(18), 0, dp(16), 0)
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_sms_send, 0)
        compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
        compoundDrawablePadding = dp(12)
        background = AppModalStyle.primary(activity, radiusDp = 13)
    }

    private fun secondaryButton(label: String): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(15, 23, 42))
        background = AppModalStyle.secondary(activity, radiusDp = 13)
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private data class DialogViews(val root: LinearLayout, val messageInput: EditText)
}
