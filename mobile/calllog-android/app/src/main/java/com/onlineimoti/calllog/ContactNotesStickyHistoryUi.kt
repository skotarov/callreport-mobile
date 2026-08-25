package com.onlineimoti.calllog

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

internal object ContactNotesStickyActionPolicy {
    /** Pin the action row exactly when its reserved place reaches the viewport top. */
    fun shouldStick(actionTopOnScreen: Int, viewportTopOnScreen: Int): Boolean =
        actionTopOnScreen <= viewportTopOnScreen

    /** Show compact identity as soon as the original identity starts entering the fixed top bar. */
    fun shouldShowCompactIdentity(
        originalNameTopOnScreen: Int?,
        topBarBottomOnScreen: Int,
        actionsPinned: Boolean,
    ): Boolean = actionsPinned ||
        (originalNameTopOnScreen != null && originalNameTopOnScreen <= topBarBottomOnScreen)
}

/** Builds History with fixed top identity/actions and a fixed bottom list-mode switch. */
internal class ContactNotesStickyHistoryUi(
    private val activity: ContactNotesActivity,
) {
    private var stickyController: StickyGroupHeaderController? = null
    private var scrollView: ScrollView? = null
    private var scrollChangedListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var layoutChangeListener: View.OnLayoutChangeListener? = null
    private var savedScrollY = 0
    private val screenLocation = IntArray(2)
    private val externalActions by lazy { ContactNotesExternalActions(activity) }

    fun resetScrollPosition() {
        savedScrollY = 0
        scrollView?.scrollTo(0, 0)
    }

    fun show(
        root: LinearLayout,
        refreshing: Boolean,
        onRefresh: () -> Unit,
        bindPaging: (ScrollView, LinearLayout) -> Unit,
        mode: ContactHistoryListMode,
        onModeSelected: (ContactHistoryListMode) -> Unit,
    ) {
        scrollView?.let { savedScrollY = it.scrollY }
        release()
        val actionAnchor = findActionAnchor(root) as? ViewGroup
        val stickyHeader = actionAnchor?.tag as? ContactNotesStickyActions
        val topBar = stickyHeader?.topBar
        val actionRow = stickyHeader?.actionRow
        val stickyActionRow = stickyHeader?.stickyActionRow
        (topBar?.parent as? ViewGroup)?.removeView(topBar)
        val originalIdentityName = stickyHeader?.compactTitle?.let { compactTitle ->
            findOriginalIdentityName(root, compactTitle)
        }

        val historyScroll = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(activity, R.color.calllog_bg))
            addView(root)
        }
        scrollView = historyScroll

        val groupOverlay = TextView(activity)
        val groupOverlayParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        val stickyActionHost = FrameLayout(activity).apply {
            visibility = View.INVISIBLE
            setBackgroundColor(ContextCompat.getColor(activity, R.color.calllog_bg))
            setPadding(dp(PAGE_HORIZONTAL_PADDING_DP), 0, dp(PAGE_HORIZONTAL_PADDING_DP), 0)
            elevation = 0f
            stateListAnimator = null
            stickyActionRow?.let { row ->
                (row.parent as? ViewGroup)?.removeView(row)
                addView(row, actionRowLayoutParams())
            }
        }
        val stickyActionHostParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(STICKY_ACTION_HEIGHT_DP),
            Gravity.TOP,
        )

        val viewport = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            addView(historyScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(groupOverlay, groupOverlayParams)
            addView(stickyActionHost, stickyActionHostParams)
        }
        val screen = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            topBar?.let { bar ->
                bar.translationZ = dp(TOP_BAR_Z_DP).toFloat()
                addView(bar, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(FIXED_TOP_BAR_HEIGHT_DP),
                ))
            }
            addView(viewport, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(historyModeBar(mode, onModeSelected), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(MODE_BAR_HEIGHT_DP),
            ))
        }

        var actionsPinnedState = false

        fun updateStickyUi() {
            if (!historyScroll.isAttachedToWindow || historyScroll.height <= 0) return
            savedScrollY = historyScroll.scrollY
            val anchor = actionAnchor
            val row = actionRow
            val actionsPinned = if (
                anchor != null && row != null && anchor.isAttachedToWindow && anchor.height > 0
            ) {
                ContactNotesStickyActionPolicy.shouldStick(
                    actionTopOnScreen = topOnScreen(anchor),
                    viewportTopOnScreen = topOnScreen(historyScroll),
                )
            } else {
                false
            }

            if (actionsPinned != actionsPinnedState) {
                stickyActionHost.visibility = if (actionsPinned) View.VISIBLE else View.INVISIBLE
                if (actionsPinned) stickyActionHost.bringToFront()
                actionsPinnedState = actionsPinned
            }

            stickyHeader?.compactTitle?.let { compactTitle ->
                val bar = topBar
                val originalNameTop = originalIdentityName
                    ?.takeIf { it.isAttachedToWindow }
                    ?.let(::topOnScreen)
                val topBarBottom = if (bar != null && bar.isAttachedToWindow) {
                    topOnScreen(bar) + bar.height
                } else {
                    topOnScreen(historyScroll)
                }
                val showCompact = compactTitle.text.isNotBlank() &&
                    ContactNotesStickyActionPolicy.shouldShowCompactIdentity(
                        originalNameTopOnScreen = originalNameTop,
                        topBarBottomOnScreen = topBarBottom,
                        actionsPinned = actionsPinned,
                    )
                val titleVisibility = if (showCompact) View.VISIBLE else View.INVISIBLE
                if (compactTitle.visibility != titleVisibility) compactTitle.visibility = titleVisibility
            }

            val groupTop = if (actionsPinned) dp(STICKY_ACTION_HEIGHT_DP) else 0
            if (groupOverlayParams.topMargin != groupTop) {
                groupOverlayParams.topMargin = groupTop
                groupOverlay.layoutParams = groupOverlayParams
            }
        }

        scrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
            updateStickyUi()
        }.also { listener ->
            historyScroll.viewTreeObserver.addOnScrollChangedListener(listener)
        }
        layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateStickyUi()
        }.also(historyScroll::addOnLayoutChangeListener)
        historyScroll.post {
            val maxScrollY = maxOf(0, root.height - historyScroll.height)
            historyScroll.scrollTo(0, savedScrollY.coerceIn(0, maxScrollY))
            updateStickyUi()
        }
        bindPaging(historyScroll, root)
        activity.setContentView(PullToRefreshLayout(activity).apply {
            addView(screen)
            setOnRefreshListener(onRefresh)
            if (refreshing) setRefreshing(true)
        })
        stickyController = StickyGroupHeaderController(
            scrollView = historyScroll,
            contentRoot = root,
            overlay = groupOverlay,
            overlayHorizontalPaddingPx = dp(HISTORY_GROUP_HORIZONTAL_PADDING_DP),
        ).also { it.bind() }
    }

    fun release() {
        val currentScroll = scrollView
        currentScroll?.let { savedScrollY = it.scrollY }
        scrollChangedListener?.let { listener ->
            val observer = currentScroll?.viewTreeObserver
            if (observer?.isAlive == true) observer.removeOnScrollChangedListener(listener)
        }
        scrollChangedListener = null
        layoutChangeListener?.let { listener -> currentScroll?.removeOnLayoutChangeListener(listener) }
        layoutChangeListener = null
        scrollView = null
        stickyController?.release()
        stickyController = null
    }

    private fun historyModeBar(
        selectedMode: ContactHistoryListMode,
        onModeSelected: (ContactHistoryListMode) -> Unit,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(ContextCompat.getColor(activity, R.color.calllog_bg))
        elevation = 0f
        stateListAnimator = null
        setPadding(
            dp(PAGE_HORIZONTAL_PADDING_DP),
            dp(MODE_BAR_VERTICAL_PADDING_DP),
            dp(PAGE_HORIZONTAL_PADDING_DP),
            dp(MODE_BAR_VERTICAL_PADDING_DP),
        )
        addView(modeButton(
            textValue = "Бележки и SMS",
            drawableRes = R.drawable.ic_menu_sms,
            mode = ContactHistoryListMode.NOTES_AND_SMS,
            selectedMode = selectedMode,
            onModeSelected = onModeSelected,
        ))
        addView(callButton())
        addView(modeButton(
            textValue = "Обаждания",
            drawableRes = R.drawable.ic_history_clock,
            mode = ContactHistoryListMode.FULL_LOG,
            selectedMode = selectedMode,
            onModeSelected = onModeSelected,
        ))
    }

    private fun callButton(): FrameLayout = FrameLayout(activity).apply {
        clipChildren = false
        clipToPadding = false
        // Move the whole touch container with the raised visual button. Moving only
        // the ImageView left its upper half outside the parent's hit area.
        translationY = -dp(CALL_BUTTON_SIZE_DP / 2).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f,
        )
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_phone_call)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(activity, R.color.callreport_icon_background))
            }
            elevation = 0f
            stateListAnimator = null
            setPadding(dp(15), dp(15), dp(15), dp(15))
            isClickable = true
            isFocusable = true
            contentDescription = activity.getString(R.string.dynamic_action_call)
            setOnClickListener {
                val phone = activity.intent?.getStringExtra(ContactNotesActivity.EXTRA_PHONE).orEmpty()
                externalActions.openDialer(phone)
            }
        }, FrameLayout.LayoutParams(
            dp(CALL_BUTTON_SIZE_DP),
            dp(CALL_BUTTON_SIZE_DP),
            Gravity.CENTER,
        ))
    }

    private fun modeButton(
        textValue: String,
        drawableRes: Int,
        mode: ContactHistoryListMode,
        selectedMode: ContactHistoryListMode,
        onModeSelected: (ContactHistoryListMode) -> Unit,
    ): LinearLayout {
        val selected = mode == selectedMode
        val activeColor = ContextCompat.getColor(activity, R.color.callreport_icon_background)
        val inactiveColor = Color.rgb(71, 85, 105)
        val itemColor = if (selected) activeColor else inactiveColor
        val activePillColor = Color.argb(
            34,
            Color.red(activeColor),
            Color.green(activeColor),
            Color.blue(activeColor),
        )
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setPadding(0, dp(1), 0, 0)
            isClickable = !selected
            isFocusable = !selected
            contentDescription = textValue
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
            addView(FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(MODE_ICON_PILL_RADIUS_DP).toFloat()
                    setColor(if (selected) activePillColor else Color.TRANSPARENT)
                }
                addView(ImageView(activity).apply {
                    setImageResource(drawableRes)
                    setColorFilter(itemColor)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, FrameLayout.LayoutParams(
                    dp(MODE_ICON_SIZE_DP),
                    dp(MODE_ICON_SIZE_DP),
                    Gravity.CENTER,
                ))
            }, LinearLayout.LayoutParams(
                dp(MODE_ICON_PILL_WIDTH_DP),
                dp(MODE_ICON_PILL_HEIGHT_DP),
            ))
            addView(TextView(activity).apply {
                text = textValue
                textSize = 12f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(itemColor)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(2)
            })
            setOnClickListener { if (!selected) onModeSelected(mode) }
        }
    }

    private fun actionRowLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(ACTION_ROW_HEIGHT_DP),
        Gravity.BOTTOM,
    )

    private fun topOnScreen(view: View): Int {
        view.getLocationOnScreen(screenLocation)
        return screenLocation[1]
    }

    private fun findOriginalIdentityName(view: View, compactTitle: TextView): TextView? {
        if (view is TextView &&
            view !== compactTitle &&
            view.text.toString() == compactTitle.text.toString() &&
            view.textSize > compactTitle.textSize
        ) {
            return view
        }
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findOriginalIdentityName(group.getChildAt(index), compactTitle)?.let { return it }
        }
        return null
    }

    private fun findActionAnchor(view: View): View? {
        if (view.tag is ContactNotesStickyActions) return view
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findActionAnchor(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE_HORIZONTAL_PADDING_DP = 16
        const val HISTORY_GROUP_HORIZONTAL_PADDING_DP = 26
        const val FIXED_TOP_BAR_HEIGHT_DP = 50
        const val STICKY_ACTION_HEIGHT_DP = 50
        const val ACTION_ROW_HEIGHT_DP = 48
        const val MODE_BAR_HEIGHT_DP = 64
        const val MODE_BAR_VERTICAL_PADDING_DP = 3
        const val MODE_ICON_SIZE_DP = 22
        const val MODE_ICON_PILL_WIDTH_DP = 56
        const val MODE_ICON_PILL_HEIGHT_DP = 28
        const val MODE_ICON_PILL_RADIUS_DP = 16
        const val CALL_BUTTON_SIZE_DP = 54
        const val TOP_BAR_Z_DP = 1
    }
}
