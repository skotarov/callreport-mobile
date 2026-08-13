package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal class HomeCrmFiltersController(
    private val activity: HomeActivity,
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
    private val dp: (Int) -> Int,
    @Suppress("UNUSED_PARAMETER") roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val filterScope: () -> HomeCrmFilterStore.Scope = {
        HomeCrmFilterStore.scopeForContactsMode(HomeCrmTimelineModeToggle.isContactsMode())
    },
    private val onFilterChanged: () -> Unit,
) {
    private val companyExecutor = Executors.newSingleThreadExecutor()
    private val companyGeneration = AtomicInteger(0)
    private var activeScope = filterScope()
    private var state = HomeCrmFilterStore.load(activity, activeScope)
    private var companies: List<CallReportTopicCompany> = emptyList()
    private var lastRequestedAccount = ""

    private val companyButtonsContainer = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
    }
    private val companyButtonsScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(companyButtonsContainer)
        visibility = View.GONE
    }

    init {
        binding.crmPhase1Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_1) }
        binding.crmPhase2Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_2) }
        binding.crmPhase3Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_3) }
        binding.crmPhase4Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_4) }
        replaceCompanyPickerWithButtons()
    }

    fun state(): HomeCrmFilterState {
        ensureCurrentScope()
        return state
    }

    fun hasActiveFilters(): Boolean = state().isActive

    /** Exact company ids currently backing the visible Clients company buttons. */
    fun availableCompanyIds(): List<String> {
        loadCachedCompanies()
        return companies
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun filterSearchResults(calls: List<PhoneCallRecord>): List<PhoneCallRecord> {
        val filterState = state()
        val phaseFiltered = HomeCrmFilterEngine.filterLocal(activity.applicationContext, calls, filterState)
        if (!filterState.isCompanyFiltered || phaseFiltered.isEmpty()) return phaseFiltered
        val memberships = HomeCrmCompanyMembershipStore.resolve(
            context = activity.applicationContext,
            config = ConfigStore.load(activity.applicationContext),
            phones = phaseFiltered.map { it.number },
        )
        return HomeCrmFilterEngine.filterByCompany(
            calls = phaseFiltered,
            state = filterState,
            companyIdsByPhoneKey = memberships.companyIdsByPhoneKey,
        )
    }

    fun updateVisibility(filtersEnabled: Boolean) {
        ensureCurrentScope()
        binding.crmPhaseFilterRow.visibility = if (filtersEnabled) View.VISIBLE else View.GONE
        binding.filteredStatusContainer.gravity = if (filtersEnabled) Gravity.CENTER_VERTICAL or Gravity.END else Gravity.CENTER_VERTICAL
        if (!filtersEnabled) {
            showCompanyButtons(false)
            return
        }
        loadCachedCompanies()
        renderButtons()
        refreshCompaniesIfNeeded()
    }

    fun refreshCompaniesIfNeeded(force: Boolean = false) {
        val config = ConfigStore.load(activity.applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) return
        val account = config.baseUrl.trim().trimEnd('/') + "|" + config.accessToken
        if (!force && account == lastRequestedAccount) return
        lastRequestedAccount = account
        val requestGeneration = companyGeneration.incrementAndGet()
        companyExecutor.execute {
            val loaded = runCatching {
                CallReportTopicCompaniesRepository.load(activity.applicationContext, config).companies
            }.getOrNull() ?: return@execute
            handler.post {
                if (requestGeneration != companyGeneration.get() || activity.isFinishing || activity.isDestroyed) return@post
                if (companies == loaded) return@post
                companies = loaded
                val selectionChanged = removeUnavailableCompanySelections()
                renderButtons()
                if (selectionChanged) onFilterChanged()
            }
        }
    }

    fun release() {
        companyGeneration.incrementAndGet()
        companyExecutor.shutdownNow()
    }

    private fun replaceCompanyPickerWithButtons() {
        val placeholder = binding.crmCompanyFilterButton.parent as? View ?: return
        val statusContainer = placeholder.parent as? LinearLayout ?: return
        val position = statusContainer.indexOfChild(placeholder)
        if (position < 0) return
        statusContainer.removeView(placeholder)
        statusContainer.addView(
            companyButtonsScroll,
            position,
            LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4) },
        )
    }

    private fun loadCachedCompanies() {
        val config = ConfigStore.load(activity.applicationContext)
        val cached = CallReportTopicCompaniesCache.read(activity.applicationContext, config)?.companies.orEmpty()
        if (cached.isNotEmpty() && cached != companies) companies = cached
    }

    private fun toggleCrmOnly() {
        if (activeScope != HomeCrmFilterStore.Scope.CLIENTS) return
        updateState(state.copy(crmOnly = !state.crmOnly))
    }

    private fun togglePhase(phase: Int) {
        val phases = state().phases.toMutableSet()
        if (!phases.add(phase)) phases.remove(phase)
        updateState(state.copy(phases = phases))
    }

    private fun toggleCompany(companyId: String) {
        val selected = state().companyIds.toMutableSet()
        if (!selected.add(companyId)) selected.remove(companyId)
        updateState(state.copy(companyIds = selected))
    }

    private fun updateState(next: HomeCrmFilterState) {
        ensureCurrentScope()
        if (next == state) return
        state = next
        HomeCrmFilterStore.save(activity, state, activeScope)
        renderButtons()
        onFilterChanged()
    }

    private fun ensureCurrentScope(): Boolean {
        val nextScope = filterScope()
        if (nextScope == activeScope) return false
        activeScope = nextScope
        state = HomeCrmFilterStore.load(activity, activeScope)
        return true
    }

    private fun removeUnavailableCompanySelections(): Boolean {
        ensureCurrentScope()
        if (companies.isEmpty() || state.companyIds.isEmpty()) return false
        val knownIds = companies.mapTo(hashSetOf()) { it.id }
        val availableSelection = state.companyIds.filterTo(linkedSetOf()) { it in knownIds }
        if (availableSelection == state.companyIds) return false
        state = state.copy(companyIds = availableSelection)
        HomeCrmFilterStore.save(activity, state, activeScope)
        return true
    }

    private fun renderButtons() {
        renderCompanyButtons()
        stylePhaseButton(binding.crmPhase1Button, ContactNegotiationPhaseStore.PHASE_1, COLOR_GREEN, Color.WHITE)
        stylePhaseButton(binding.crmPhase2Button, ContactNegotiationPhaseStore.PHASE_2, COLOR_BLUE, Color.WHITE)
        stylePhaseButton(binding.crmPhase3Button, ContactNegotiationPhaseStore.PHASE_3, COLOR_YELLOW, COLOR_DARK_TEXT)
        stylePhaseButton(binding.crmPhase4Button, ContactNegotiationPhaseStore.PHASE_4, COLOR_RED, Color.WHITE)
    }

    private fun renderCompanyButtons() {
        ensureCurrentScope()
        val available = companies.filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
        val showPersonalCrm = activeScope == HomeCrmFilterStore.Scope.CLIENTS
        companyButtonsContainer.removeAllViews()
        if (showPersonalCrm) companyButtonsContainer.addView(crmFilterButton())
        available.forEach { companyButtonsContainer.addView(companyButton(it)) }
        showCompanyButtons(binding.crmPhaseFilterRow.visibility == View.VISIBLE && (showPersonalCrm || available.isNotEmpty()))
    }

    private fun crmFilterButton(): LinearLayout {
        val active = state.crmOnly
        val activeColor = activity.getColor(R.color.callreport_icon_background)
        val fill = if (active) activeColor else Color.WHITE
        val border = if (active) activeColor else COLOR_INACTIVE
        val foreground = if (active) Color.WHITE else COLOR_DARK_TEXT
        val description = activity.getString(
            if (active) R.string.clients_filter_show_all else R.string.clients_filter_show_mine,
        )
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            contentDescription = description
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(fill)
                setStroke(dp(1), border)
            }
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginEnd = dp(4)
            }
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_client_care)
                imageTintList = ColorStateList.valueOf(foreground)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })
            setOnClickListener { toggleCrmOnly() }
        }
    }

    private fun companyButton(company: CallReportTopicCompany): MaterialButton =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = company.name
            contentDescription = company.name
            isAllCaps = false
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            setMaxWidth(dp(152))
            textSize = 12f
            minimumHeight = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(16)
            setPadding(dp(9), 0, dp(9), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(4) }
            styleCompanyButton(this, company.id in state.companyIds)
            setOnClickListener { toggleCompany(company.id) }
        }

    private fun showCompanyButtons(shouldShow: Boolean) {
        companyButtonsScroll.visibility = if (shouldShow) View.VISIBLE else View.GONE
        val parameters = binding.homeStatusText.layoutParams as? LinearLayout.LayoutParams ?: return
        val width = if (shouldShow) ViewGroup.LayoutParams.WRAP_CONTENT else 0
        val weight = if (shouldShow) 0f else 1f
        if (parameters.width != width || parameters.weight != weight) {
            parameters.width = width
            parameters.weight = weight
            binding.homeStatusText.layoutParams = parameters
        }
        binding.homeStatusText.maxWidth = if (shouldShow) dp(112) else Int.MAX_VALUE
    }

    private fun stylePhaseButton(button: MaterialButton, phase: Int, activeColor: Int, activeTextColor: Int) {
        val selected = phase in state.phases
        val color = if (selected) activeColor else COLOR_INACTIVE
        button.isSelected = selected
        button.backgroundTintList = ColorStateList.valueOf(color)
        button.strokeColor = ColorStateList.valueOf(color)
        button.setTextColor(if (selected) activeTextColor else COLOR_DARK_TEXT)
    }

    private fun styleCompanyButton(button: MaterialButton, active: Boolean) {
        val fill = if (active) activity.getColor(R.color.callreport_icon_background) else Color.WHITE
        val border = if (active) activity.getColor(R.color.callreport_icon_background) else COLOR_INACTIVE
        button.isSelected = active
        button.backgroundTintList = ColorStateList.valueOf(fill)
        button.strokeColor = ColorStateList.valueOf(border)
        button.setTextColor(if (active) Color.WHITE else COLOR_DARK_TEXT)
    }

    private companion object {
        val COLOR_INACTIVE: Int = Color.rgb(203, 213, 225)
        val COLOR_DARK_TEXT: Int = Color.rgb(51, 65, 85)
        val COLOR_GREEN: Int = Color.rgb(22, 163, 74)
        val COLOR_BLUE: Int = Color.rgb(37, 99, 235)
        val COLOR_YELLOW: Int = Color.rgb(250, 204, 21)
        val COLOR_RED: Int = Color.rgb(220, 38, 38)
    }
}
