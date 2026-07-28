package com.onlineimoti.calllog

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/** Device-wide chronological SMS list opened from the Call Log overflow menu. */
class SmsHistoryActivity : FontScaledAppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scrollView: ScrollView
    private lateinit var listContainer: LinearLayout
    private lateinit var paginationContainer: LinearLayout
    private lateinit var previousButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var pageText: TextView
    private lateinit var edgePager: EdgePageScrollController
    private var pageIndex = 0
    private var loading = false
    private var hasNext = false

    private val readSmsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        renderPage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        setContentView(createContent())
        updatePaginationVisibility()
        edgePager = EdgePageScrollController(
            canPrevious = { pageIndex > 0 },
            canNext = { PageLoadingModeStore.usesPrefetch(this) && hasNext },
            previousPage = ::previousPage,
            nextPage = ::nextPage,
            pageReady = { !loading && progress.visibility != View.VISIBLE },
        ).also { it.bind(scrollView, listContainer) }
        renderPage()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        edgePager.cancelPending()
        pageIndex = 0
        if (!loading) renderPage()
    }

    override fun onResume() {
        super.onResume()
        if (::paginationContainer.isInitialized) updatePaginationVisibility()
        if (::listContainer.isInitialized && !loading) renderPage()
    }

    override fun onDestroy() {
        if (::edgePager.isInitialized) edgePager.release()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.calllog_bg))
        }
        root.addView(header())
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.calllog_muted_text))
            setPadding(dp(18), 0, dp(18), dp(6))
        }
        root.addView(statusText)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(6)
        })
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        scrollView = ScrollView(this).apply { addView(listContainer) }
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        paginationContainer = pagination()
        root.addView(paginationContainer)
        return root
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(12), dp(16), dp(8))
        addView(ImageButton(this@SmsHistoryActivity).apply {
            setImageResource(R.drawable.ic_settings_back)
            contentDescription = "Назад"
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(TextView(this@SmsHistoryActivity).apply {
            text = "SMS"
            textSize = 24f
            setTextColor(getColor(R.color.calllog_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageButton(this@SmsHistoryActivity).apply {
            setImageResource(R.drawable.ic_sms_send)
            contentDescription = "Нов SMS"
            background = null
            setColorFilter(getColor(R.color.calllog_text))
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { SmsNewMessageLauncher.show(this@SmsHistoryActivity, ::dp) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun pagination(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(12), dp(8), dp(12), dp(12))
        previousButton = pageButton("Предишни", ::previousPage)
        nextButton = pageButton("Следващи", ::nextPage)
        pageText = TextView(this@SmsHistoryActivity).apply {
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTextColor(getColor(R.color.calllog_muted_text))
        }
        addView(previousButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        addView(pageText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f))
        addView(nextButton, LinearLayout.LayoutParams(0, dp(42), 1f))
    }

    private fun pageButton(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun previousPage() {
        if (loading || pageIndex <= 0) return
        pageIndex--
        renderPage()
    }

    private fun nextPage() {
        if (loading || !hasNext) return
        pageIndex++
        renderPage()
    }

    private fun renderPage() {
        updatePaginationVisibility()
        if (!hasSmsPermission()) {
            renderPermissionRequired()
            return
        }
        if (loading) return
        loading = true
        progress.visibility = View.VISIBLE
        statusText.text = "Зареждане на SMS…"
        previousButton.isEnabled = false
        nextButton.isEnabled = false
        val requestedPage = pageIndex
        val pageSize = pageSize()
        executor.execute {
            val loaded = SmsMessageReader.recentMessages(
                context = applicationContext,
                offset = requestedPage * pageSize,
                limit = pageSize + 1,
            )
            val displayNames = loaded.associate { message ->
                message.providerId to ContactGroupFilter.resolveDisplayName(applicationContext, message.address).orEmpty()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || requestedPage != pageIndex) return@runOnUiThread
                loading = false
                progress.visibility = View.GONE
                hasNext = loaded.size > pageSize
                val messages = loaded.take(pageSize)
                renderRows(messages, displayNames)
                val first = requestedPage * pageSize + 1
                val last = requestedPage * pageSize + messages.size
                statusText.text = when {
                    messages.isEmpty() && requestedPage == 0 -> "Няма SMS в телефона"
                    messages.isEmpty() -> "Няма повече SMS"
                    else -> "SMS по дата: $first–$last"
                }
                pageText.text = "Страница ${requestedPage + 1}"
                previousButton.text = "Предишни $pageSize"
                nextButton.text = "Следващи $pageSize"
                previousButton.isEnabled = requestedPage > 0
                nextButton.isEnabled = hasNext
                updatePaginationVisibility()
            }
        }
    }

    private fun updatePaginationVisibility() {
        paginationContainer.visibility = if (PageLoadingModeStore.usesPrefetch(this)) View.GONE else View.VISIBLE
    }

    private fun renderPermissionRequired() {
        loading = false
        hasNext = false
        progress.visibility = View.GONE
        listContainer.removeAllViews()
        statusText.text = "Нужно е разрешение за четене на SMS"
        pageText.text = ""
        previousButton.isEnabled = false
        nextButton.isEnabled = false
        listContainer.addView(TextView(this).apply {
            text = "За да видиш хронологичния списък със SMS-и, разреши достъп до SMS на приложението."
            textSize = 15f
            setTextColor(getColor(R.color.calllog_text))
            setPadding(dp(4), dp(12), dp(4), dp(12))
        })
        listContainer.addView(MaterialButton(this).apply {
            text = "Разреши достъп до SMS"
            isAllCaps = false
            setOnClickListener { readSmsPermissionLauncher.launch(Manifest.permission.READ_SMS) }
        })
    }

    private fun renderRows(messages: List<SmsTimelineMessage>, displayNames: Map<String, String>) {
        listContainer.removeAllViews()
        messages.forEach { message ->
            val row = smsRow(message, displayNames[message.providerId].orEmpty())
            listContainer.addView(ListThemeUi.applyRowSpacing(row, ::dp))
        }
    }

    private fun smsRow(message: SmsTimelineMessage, displayName: String): View {
        val sms = PhoneCallRecord(
            number = message.address,
            name = displayName,
            direction = if (message.isOutgoing) "sms_out" else "sms_in",
            startedAt = message.timestampMs,
            durationSeconds = 0L,
            smsBody = message.body,
            providerId = message.providerId,
        )
        return SmsTimelineCard.create(
            activity = this,
            dp = ::dp,
            message = sms,
            displayName = sms.displayName,
            actions = listOf(
                SmsTimelineCard.Action(
                    drawableRes = R.drawable.ic_filter_calls,
                    contentDescription = getString(R.string.dynamic_action_filter),
                    onClick = { openFullHistory(sms) },
                ),
            ),
            onClick = { openContactNotes(sms) },
        )
    }

    private fun openFullHistory(sms: PhoneCallRecord) {
        startActivity(
            Intent(this, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, sms.number)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, sms.displayName)
                .putExtra(ContactNotesActivity.EXTRA_INITIAL_LIST_MODE, ContactNotesActivity.LIST_MODE_FULL_LOG)
                .putExtra(ContactNotesActivity.EXTRA_BACK_TARGETS_UNFILTERED_HOME, true),
        )
    }

    private fun openContactNotes(sms: PhoneCallRecord) {
        startActivity(
            Intent(this, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, sms.number)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, sms.displayName)
                .putExtra(ContactNotesActivity.EXTRA_BACK_TARGETS_UNFILTERED_HOME, true),
        )
    }

    private fun pageSize(): Int = ConfigStore.load(this).homeCallPageSize.coerceIn(5, 100)
    private fun hasSmsPermission(): Boolean = SmsMessageReader.hasReadSmsPermission(this)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
