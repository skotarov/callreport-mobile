package com.onlineimoti.calllog

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.onlineimoti.calllog.databinding.ActivityRecentCallsBinding

class RecentCallsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecentCallsBinding
    private var phoneFilter: String = ""
    private val contactNameCache = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        binding = ActivityRecentCallsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        phoneFilter = intent.getStringExtra(EXTRA_PHONE_FILTER).orEmpty()
    }

    override fun onResume() {
        super.onResume()
        renderCalls()
    }

    private fun renderCalls() {
        binding.recentCallsContainer.removeAllViews()
        val isFiltered = phoneFilter.isNotBlank()
        binding.recentCallsTitleText.text = getString(
            if (isFiltered) R.string.recent_calls_title_filtered else R.string.recent_calls_title,
        )

        if (!PhoneCallReader.hasCallLogPermission(this)) {
            binding.recentCallsStatusText.text = getString(R.string.recent_calls_permission_missing)
            return
        }

        val pageSize = ConfigStore.homeCallPageSize(this)
        val calls = if (isFiltered) {
            // A filtered contact timeline contains both phone calls and SMS messages, ordered together by date.
            HomeCallPageLoader.calls(
                context = this,
                activePhoneFilter = phoneFilter,
                searchQuery = "",
                pageIndex = 0,
                pageSize = pageSize,
            )
        } else {
            PhoneCallReader.recentCalls(this, limit = pageSize)
        }
        if (calls.isEmpty()) {
            binding.recentCallsStatusText.text = if (isFiltered) {
                getString(R.string.recent_calls_empty_filtered, phoneFilter)
            } else {
                getString(R.string.recent_calls_empty)
            }
            return
        }

        binding.recentCallsStatusText.text = if (isFiltered) {
            getString(R.string.recent_calls_showing_filtered, calls.size, phoneFilter)
        } else {
            getString(R.string.recent_calls_showing, calls.size)
        }
        calls.forEach { call ->
            binding.recentCallsContainer.addView(
                ListThemeUi.applyRowSpacing(callCard(call), ::dp),
            )
        }
    }

    private fun callCard(call: PhoneCallRecord): MaterialCardView {
        if (call.isSms) {
            return SmsTimelineCard.create(
                activity = this,
                dp = ::dp,
                message = call,
                displayName = resolveContactName(call.number).ifBlank { call.displayName },
            )
        }

        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(getColor(R.color.calllog_border))
            setCardBackgroundColor(getColor(R.color.calllog_surface))
            cardElevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        content.addView(TextView(this).apply {
            text = call.displayName
            setTextColor(getColor(R.color.calllog_text))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = listOf(
                call.number,
                PhoneCallReader.formatStartedAt(call.startedAt),
                PhoneCallReader.directionLabel(call.direction),
                PhoneCallReader.formatDuration(call.durationSeconds),
            ).filter { it.isNotBlank() }.joinToString(" • ")
            setTextColor(getColor(R.color.calllog_muted_text))
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        })
        content.addView(MaterialButton(this).apply {
            text = getString(R.string.recent_calls_write_note)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            }
            setOnClickListener { openNoteEditorForCall(call) }
        })

        card.addView(content)
        return card
    }

    private fun resolveContactName(number: String): String {
        val key = PhoneNormalizer.normalize(number).ifBlank { number.trim() }
        return contactNameCache.getOrPut(key) {
            ContactGroupFilter.resolveDisplayName(this, number).orEmpty()
        }
    }

    private fun openNoteEditorForCall(call: PhoneCallRecord) {
        val existingNote = existingCallNote(call)
        CallNoteEditorLauncher.startEditor(
            context = this,
            mode = PostCallOverlayService.MODE_NOTE,
            phone = call.number,
            title = resolveContactName(call.number).ifBlank { call.displayName },
            direction = call.direction,
            callAt = call.startedAt,
            durationSeconds = call.durationSeconds,
            companyId = existingNote?.companyId.orEmpty(),
            initialNoteText = existingNote?.note.orEmpty(),
            serverClientEventId = existingNote?.serverClientEventId.orEmpty(),
        )
    }

    private fun existingCallNote(call: PhoneCallRecord): ContactCallNote? {
        if (call.startedAt <= 0L) return null
        return ContactNoteReader.callNotesForPhone(applicationContext, call.number)
            .asSequence()
            .filter { note ->
                note.callAt == call.startedAt &&
                    (call.direction.isBlank() || note.direction.isBlank() || note.direction == call.direction)
            }
            .maxByOrNull { note -> maxOf(note.savedAt, note.callAt) }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_PHONE_FILTER = "phone_filter"
    }
}
