package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

internal data class CompanyLicenseViews(
    val root: View,
    val status: TextView,
    val price: TextView,
    val paymentOptionsBox: LinearLayout,
    val playResponse: TextView,
    val spinner: ProgressBar,
    val buy: MaterialButton,
    val restore: MaterialButton,
    val create: MaterialButton,
)

internal object CompanyLicenseContentUi {
    fun create(
        activity: Activity,
        launchPurchase: () -> Unit,
        restorePurchase: () -> Unit,
    ): CompanyLicenseViews {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = ScrollView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(box)
        box.addView(TextView(activity).apply { text = "Създай своя фирма"; textSize = 24f })
        box.addView(TextView(activity).apply {
            text = "Еднократният фирмен лиценз отключва една организация. Поканените колеги влизат без отделна покупка."
            textSize = 16f
            setPadding(0, dp(8), 0, dp(18))
        })
        val price = TextView(activity).apply { text = "Проверяваме лиценза…"; textSize = 18f }
        box.addView(price)
        val spinner = ProgressBar(activity).apply { visibility = View.GONE }
        box.addView(
            spinner,
            LinearLayout.LayoutParams(dp(42), dp(42)).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
        val buy = MaterialButton(activity).apply {
            text = "Купи фирмен лиценз"
            isEnabled = false
            setOnClickListener { launchPurchase() }
        }
        box.addView(
            buy,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        val restore = MaterialButton(activity).apply {
            text = "Възстанови покупка"
            isEnabled = false
            setOnClickListener { restorePurchase() }
        }
        box.addView(
            restore,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        val paymentOptionsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(paymentOptionsBox)
        val create = MaterialButton(activity).apply {
            text = "Създай фирма"
            visibility = View.GONE
            setOnClickListener {
                activity.startActivity(
                    Intent(activity, CompanyAccountActivity::class.java)
                        .putExtra(CompanyAccountActivity.EXTRA_MODE, CompanyAccountActivity.MODE_CREATE_COMPANY),
                )
            }
        }
        box.addView(
            create,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) },
        )
        box.addView(
            MaterialButton(activity).apply {
                text = "Отвори настройки"
                setOnClickListener { activity.startActivity(Intent(activity, MainActivity::class.java)) }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        val status = TextView(activity).apply { textSize = 15f; setPadding(0, dp(18), 0, 0) }
        box.addView(status)
        box.addView(TextView(activity).apply {
            text = "Отговор от Google Play"
            textSize = 16f
            setPadding(0, dp(18), 0, dp(6))
        })
        val playResponse = TextView(activity).apply {
            text = "Още няма отговор от Google Play."
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(playResponse)

        return CompanyLicenseViews(
            root = root,
            status = status,
            price = price,
            paymentOptionsBox = paymentOptionsBox,
            playResponse = playResponse,
            spinner = spinner,
            buy = buy,
            restore = restore,
            create = create,
        )
    }
}