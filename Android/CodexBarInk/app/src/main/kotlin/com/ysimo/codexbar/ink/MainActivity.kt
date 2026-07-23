package com.ysimo.codexbar.ink

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.ysimo.codexbar.ink.core.DashboardPresentation
import com.ysimo.codexbar.ink.core.DashboardPresenter
import com.ysimo.codexbar.ink.core.ProviderPresentation
import com.ysimo.codexbar.ink.core.ReaderState
import com.ysimo.codexbar.ink.core.RegionKey
import com.ysimo.codexbar.ink.core.SemanticChangeSet
import com.ysimo.codexbar.ink.databinding.ActivityMainBinding

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: DashboardRepository
    private lateinit var displayAdapter: DisplayAdapter
    private var readerState: ReaderState? = null
    private var presentation: DashboardPresentation? = null
    private var sourceLabel: String = "Starting"
    private var adapterAttached = false

    private val refreshLoop = object : Runnable {
        override fun run() {
            refreshSnapshot()
            binding.dashboardRoot.postDelayed(this, REFRESH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets()

        repository = DashboardRepository(applicationContext)
        displayAdapter = DisplayAdapterFactory.create()
        binding.dashboardRoot.post {
            if (binding.dashboardRoot.isAttachedToWindow) {
                displayAdapter.attach(binding.dashboardRoot)
                adapterAttached = true
            }
        }

        binding.refreshButton.setOnClickListener { refreshSnapshot() }
        binding.cleanButton.setOnClickListener {
            if (adapterAttached) displayAdapter.fullRefresh("manual-ghost-cleanup")
            binding.transportStatusText.text = "$sourceLabel · ${displayAdapter.capabilityLabel} · cleaned"
        }

        val initial = repository.loadInitial()
        render(initial)
    }

    override fun onStart() {
        super.onStart()
        binding.dashboardRoot.removeCallbacks(refreshLoop)
        binding.dashboardRoot.post(refreshLoop)
    }

    override fun onStop() {
        binding.dashboardRoot.removeCallbacks(refreshLoop)
        super.onStop()
    }

    override fun onDestroy() {
        displayAdapter.detach()
        repository.close()
        super.onDestroy()
    }

    private fun refreshSnapshot() {
        binding.transportStatusText.text = "$sourceLabel · refreshing"
        submitDisplayUpdate(SemanticChangeSet(setOf(RegionKey.Header)))
        repository.refresh(readerState) { result -> render(result) }
    }

    private fun render(result: DashboardRepository.Result) {
        val state = result.state
        val sourceChanged = sourceLabel != result.sourceLabel
        sourceLabel = result.sourceLabel
        if (state == null) {
            binding.freshnessText.text = "NO SNAPSHOT"
            binding.transportStatusText.text = result.errorLabel ?: result.sourceLabel
            return
        }

        readerState = state
        val next = DashboardPresenter.present(state, System.currentTimeMillis())
        var changes = DashboardPresenter.diff(presentation, next)
        if (sourceChanged || result.errorLabel != null) {
            changes = SemanticChangeSet(changes.regions + RegionKey.Header)
        }
        bindPresentation(next, result)
        presentation = next
        Log.i(
            TAG,
            "render source=${result.sourceLabel} regions=${changes.regions.size} adapter=${displayAdapter.capabilityLabel}",
        )
        submitDisplayUpdate(changes)
    }

    private fun bindPresentation(next: DashboardPresentation, result: DashboardRepository.Result) {
        binding.freshnessText.text = next.freshness
        binding.transportStatusText.text = buildString {
            append(result.sourceLabel)
            result.errorLabel?.let { append(" · ").append(it) }
            append(" · ").append(displayAdapter.capabilityLabel)
        }
        bindPriorityCard(
            next.codex,
            binding.codexName,
            binding.codexPrimary,
            binding.codexSecondary,
            binding.codexStatus,
            binding.codexProgress,
            "Codex",
        )
        bindPriorityCard(
            next.claude,
            binding.claudeName,
            binding.claudePrimary,
            binding.claudeSecondary,
            binding.claudeStatus,
            binding.claudeProgress,
            "Claude",
        )
        bindGenericProviders(next.genericProviders)
    }

    private fun bindPriorityCard(
        provider: ProviderPresentation?,
        nameView: TextView,
        primaryView: TextView,
        secondaryView: TextView,
        statusView: TextView,
        progressView: android.widget.ProgressBar,
        fallbackName: String,
    ) {
        nameView.text = provider?.name ?: fallbackName
        primaryView.text = provider?.primary ?: "Not enabled"
        secondaryView.text = provider?.secondary.orEmpty()
        statusView.text = provider?.status ?: "No data"
        progressView.setProgress(provider?.usedPercent ?: 0, false)
    }

    private fun bindGenericProviders(providers: List<ProviderPresentation>) {
        val container = binding.genericProviderContainer
        val existingIDs = (0 until container.childCount).mapNotNull { index ->
            container.getChildAt(index).tag as? String
        }
        if (existingIDs != providers.map { it.id }) {
            container.removeAllViews()
            providers.take(MAX_GENERIC_ROWS).forEach { provider ->
                container.addView(createGenericRow(provider.id))
            }
            if (providers.size > MAX_GENERIC_ROWS) {
                container.addView(createGenericRow(OVERFLOW_ID))
            }
        }

        providers.take(MAX_GENERIC_ROWS).forEachIndexed { index, provider ->
            val row = container.getChildAt(index) as TextView
            row.text = "${provider.name} — ${provider.primary} · ${provider.status}"
            row.contentDescription = "${provider.name}. ${provider.primary}. ${provider.status}"
        }
        if (providers.size > MAX_GENERIC_ROWS) {
            val overflow = container.getChildAt(container.childCount - 1) as TextView
            overflow.text = "+ ${providers.size - MAX_GENERIC_ROWS} more providers"
            overflow.contentDescription = overflow.text
        }
    }

    private fun createGenericRow(providerID: String): TextView = TextView(this).apply {
        id = View.generateViewId()
        tag = providerID
        setTextColor(Color.rgb(21, 21, 21))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        maxLines = 2
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun submitDisplayUpdate(changes: SemanticChangeSet) {
        if (changes.isEmpty) return
        binding.dashboardRoot.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (binding.dashboardRoot.viewTreeObserver.isAlive) {
                    binding.dashboardRoot.viewTreeObserver.removeOnPreDrawListener(this)
                }
                if (adapterAttached) {
                    if (changes.regions.contains(RegionKey.Root)) {
                        displayAdapter.fullRefresh("cold-start-or-root-change")
                    } else {
                        displayAdapter.render(changes, regionRegistry())
                    }
                }
                return true
            }
        })
        binding.dashboardRoot.invalidate()
    }

    private fun regionRegistry(): Map<RegionKey, View> = buildMap {
        put(RegionKey.Root, binding.dashboardRoot)
        put(RegionKey.Header, binding.headerRegion)
        put(RegionKey.Provider("codex"), binding.codexCard)
        put(RegionKey.Provider("claude"), binding.claudeCard)
        put(RegionKey.ProviderList, binding.genericCard)
        val container = binding.genericProviderContainer
        (0 until container.childCount).forEach { index ->
            val view = container.getChildAt(index)
            val providerID = view.tag as? String
            if (providerID != null && providerID != OVERFLOW_ID) put(RegionKey.Provider(providerID), view)
        }
    }

    private fun applySystemInsets() {
        val root = binding.dashboardRoot
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safeInsets = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(
                baseLeft + safeInsets.left,
                baseTop + safeInsets.top,
                baseRight + safeInsets.right,
                baseBottom + safeInsets.bottom,
            )
            insets
        }
        root.requestApplyInsets()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "CodexBarInk"
        const val REFRESH_INTERVAL_MILLIS = 5 * 60 * 1_000L
        const val MAX_GENERIC_ROWS = 2
        const val OVERFLOW_ID = "__overflow__"
    }
}
