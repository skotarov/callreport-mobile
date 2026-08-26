package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class CrmContactDialogUi(private val activity: Activity) {
    fun verticalSection(): LinearLayout {
        return LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    }

    fun input(parent: LinearLayout, label: String, value: String = "", lines: Int = 1): EditText {
        parent.addView(TextView(activity).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, dp(8), 0, dp(3))
        })
        return EditText(activity).apply {
            setText(value)
            textSize = 15f
            minLines = lines
            maxLines = if (lines > 1) 5 else 1
            inputType = InputType.TYPE_CLASS_TEXT or if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            setSingleLine(lines == 1)
            setSelectAllOnFocus(false)
            AppModalStyle.styleInput(this)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            parent.addView(this)
        }
    }

    fun header(parent: LinearLayout, text: String) {
        parent.addView(TextView(activity).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 23, 42))
            setPadding(0, dp(14), 0, dp(2))
        })
    }

    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

}
