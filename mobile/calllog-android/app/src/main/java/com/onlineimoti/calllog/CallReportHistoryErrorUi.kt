package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView

internal class CallReportHistoryErrorUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun addBelowContactName(
        root: LinearLayout,
        remoteEnabled: Boolean,
        errorText: String,
    ) {
        if (!remoteEnabled || errorText.isBlank()) return
        root.addView(
            TextView(activity).apply {
                text = errorText
                textSize = 12.5f
                setTextColor(Color.rgb(185, 28, 28))
                setPadding(dp(2), 0, dp(2), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            },
            minOf(1, root.childCount),
        )
    }
}

internal object CallReportHistoryErrorText {
    fun from(error: Throwable): String {
        val message = error.message.orEmpty().trim()
        val httpStatus = Regex("\\bHTTP\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (httpStatus != null) {
            return when (httpStatus) {
                400 -> "Сървър: невалидна заявка (400)"
                401 -> "Сървър: невалиден access token (401)"
                403 -> "Сървър: достъпът е отказан (403)"
                404 -> "Сървър: history_lookup.php не е намерен (404)"
                408 -> "Сървър: изтече времето за изчакване (408)"
                429 -> "Сървър: твърде много заявки (429)"
                in 500..599 -> "Сървър: вътрешна грешка ($httpStatus)"
                else -> "Сървър: HTTP $httpStatus"
            }
        }

        return when (rootCause(error)) {
            is java.net.UnknownHostException -> "Сървър: адресът не е открит"
            is java.net.ConnectException -> "Сървър: няма връзка със сървъра"
            is java.net.SocketTimeoutException -> "Сървър: изтече времето за изчакване"
            is org.json.JSONException -> "Сървър: невалиден JSON отговор"
            else -> {
                val safeMessage = message.replace(Regex("\\s+"), " ").take(120)
                if (
                    safeMessage.isBlank() ||
                    safeMessage.equals("History lookup failed", ignoreCase = true)
                ) {
                    "Сървър: неуспешно зареждане на историята"
                } else {
                    "Сървър: $safeMessage"
                }
            }
        }
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }
}
