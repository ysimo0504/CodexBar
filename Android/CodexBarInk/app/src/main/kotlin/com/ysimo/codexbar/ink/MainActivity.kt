package com.ysimo.codexbar.ink

import android.app.AlertDialog
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ProgressBar
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
    private data class ProviderBlock(
        val providerID: String,
        val root: View,
        val name: TextView,
        val remaining: TextView,
        val primary: TextView,
        val reset: TextView,
        val secondary: TextView,
        val status: TextView,
        val progress: ProgressBar,
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: DashboardRepository
    private lateinit var displayAdapter: DisplayAdapter
    private var readerState: ReaderState? = null
    private var presentation: DashboardPresentation? = null
    private var sourceLabel: String = "Starting"
    private var adapterAttached = false
    private val genericProviderBlocks = mutableListOf<ProviderBlock>()

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
        tintProgressBar(binding.codexProgress, getColor(R.color.codex_accent))
        tintProgressBar(binding.claudeProgress, getColor(R.color.claude_accent))
        binding.dashboardRoot.post {
            if (binding.dashboardRoot.isAttachedToWindow) {
                displayAdapter.attach(binding.dashboardRoot)
                adapterAttached = true
            }
        }

        binding.refreshButton.setOnClickListener { refreshSnapshot() }
        binding.settingsButton.setOnClickListener { showHostDialog() }

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

    private fun showHostDialog() {
        val currentHost = repository.hostOrigin().orEmpty()
        val hostField = EditText(this).apply {
            hint = getString(R.string.host_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
            setText(currentHost)
            setSelectAllOnFocus(true)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.usage_host_title)
            .setView(hostField)
            .setPositiveButton(R.string.save_host, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = hostField.text.toString().trim()
                val error = if (host == currentHost && currentHost.isNotEmpty()) {
                    null
                } else if (host.isEmpty()) {
                    getString(R.string.host_required)
                } else {
                    repository.saveHost(host)
                }
                if (error == null) {
                    readerState = null
                    presentation = null
                    dialog.dismiss()
                    refreshSnapshot()
                } else {
                    hostField.error = error
                }
            }
        }
        dialog.show()
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
        binding.codexCard.visibility = if (next.codex == null) View.GONE else View.VISIBLE
        binding.claudeCard.visibility = if (next.claude == null) View.GONE else View.VISIBLE
        binding.priorityDivider.visibility = if (next.codex != null && next.claude != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        next.codex?.let { provider ->
            bindProvider(
                provider,
                binding.codexName,
                binding.codexRemaining,
                binding.codexPrimary,
                binding.codexReset,
                binding.codexSecondary,
                binding.codexStatus,
                binding.codexProgress,
            )
        }
        next.claude?.let { provider ->
            bindProvider(
                provider,
                binding.claudeName,
                binding.claudeRemaining,
                binding.claudePrimary,
                binding.claudeReset,
                binding.claudeSecondary,
                binding.claudeStatus,
                binding.claudeProgress,
            )
        }
        bindGenericProviders(next.genericProviders)
    }

    private fun bindProvider(
        provider: ProviderPresentation,
        nameView: TextView,
        remainingView: TextView,
        primaryView: TextView,
        resetView: TextView,
        secondaryView: TextView,
        statusView: TextView,
        progressView: ProgressBar,
    ) {
        nameView.text = provider.name
        remainingView.text = provider.remaining
        primaryView.text = provider.primary
        resetView.text = provider.reset
        secondaryView.text = provider.secondary
        statusView.text = provider.status
        progressView.setProgress(provider.usedPercent ?: 0, false)
        progressView.visibility = if (provider.usedPercent != null) View.VISIBLE else View.GONE
        resetView.visibility = if (provider.reset != "RESET —") View.VISIBLE else View.GONE
        secondaryView.visibility = if (provider.secondary.isBlank()) View.GONE else View.VISIBLE
        statusView.visibility = if (
            provider.status.isBlank() || provider.status == provider.primary
        ) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun bindGenericProviders(providers: List<ProviderPresentation>) {
        val container = binding.genericProviderContainer
        binding.genericCard.visibility = if (providers.isEmpty()) View.GONE else View.VISIBLE
        if (this.genericProviderBlocks.map { it.providerID } != providers.map { it.id }) {
            container.removeAllViews()
            this.genericProviderBlocks.clear()
            providers.forEach { provider ->
                val block = this.createGenericProviderBlock(provider.id)
                this.genericProviderBlocks += block
                container.addView(block.root)
            }
        }

        providers.zip(this.genericProviderBlocks).forEach { (provider, block) ->
            this.bindProvider(
                provider,
                block.name,
                block.remaining,
                block.primary,
                block.reset,
                block.secondary,
                block.status,
                block.progress,
            )
            block.root.contentDescription = buildString {
                append(provider.name).append(". ")
                append(provider.remaining).append(". ")
                append(provider.primary).append(". ")
                append(provider.status)
            }
        }
    }

    private fun createGenericProviderBlock(providerID: String): ProviderBlock {
        val root = this.layoutInflater.inflate(
            R.layout.provider_full_item,
            binding.genericProviderContainer,
            false,
        )
        val accentColor = providerAccentColor(providerID)
        root.tag = providerID
        return ProviderBlock(
            providerID = providerID,
            root = root,
            name = root.findViewById(R.id.provider_name),
            remaining = root.findViewById<TextView>(R.id.provider_remaining).apply {
                setTextColor(accentColor)
            },
            primary = root.findViewById(R.id.provider_primary),
            reset = root.findViewById(R.id.provider_reset),
            secondary = root.findViewById(R.id.provider_secondary),
            status = root.findViewById(R.id.provider_status),
            progress = root.findViewById<ProgressBar>(R.id.provider_progress).apply {
                tintProgressBar(this, accentColor)
            },
        )
    }

    private fun tintProgressBar(progressBar: ProgressBar, color: Int) {
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(getColor(R.color.ink_light))
        }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
        }
        val clippedFill = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)
        progressBar.progressDrawable = LayerDrawable(arrayOf(background, clippedFill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun providerAccentColor(providerID: String): Int {
        val colorResource = when (providerID.lowercase()) {
            "cursor" -> R.color.cursor_accent
            else -> GENERIC_ACCENT_COLORS[
                Math.floorMod(providerID.hashCode(), GENERIC_ACCENT_COLORS.size)
            ]
        }
        return getColor(colorResource)
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
        this@MainActivity.genericProviderBlocks.forEach { block ->
            put(RegionKey.Provider(block.providerID), block.root)
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

    private companion object {
        const val TAG = "CodexBarInk"
        val GENERIC_ACCENT_COLORS = intArrayOf(
            R.color.provider_accent_magenta,
            R.color.provider_accent_cyan,
            R.color.provider_accent_green,
        )
        const val REFRESH_INTERVAL_MILLIS = 5 * 60 * 1_000L
    }
}
