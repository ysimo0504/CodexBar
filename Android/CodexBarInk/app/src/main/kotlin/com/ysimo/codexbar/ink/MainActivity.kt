package com.ysimo.codexbar.ink

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.ysimo.codexbar.ink.core.DashboardPresentation
import com.ysimo.codexbar.ink.core.DashboardPresenter
import com.ysimo.codexbar.ink.core.MetricPresentation
import com.ysimo.codexbar.ink.core.ProviderPresentation
import com.ysimo.codexbar.ink.core.QuotaPresentation
import com.ysimo.codexbar.ink.core.ReaderState
import com.ysimo.codexbar.ink.core.RegionKey
import com.ysimo.codexbar.ink.core.SemanticChangeSet
import com.ysimo.codexbar.ink.core.UsageHistoryKind
import com.ysimo.codexbar.ink.databinding.ActivityMainBinding
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private data class ProviderBlock(
        val providerID: String,
        val root: View,
        val name: TextView,
        val subtitle: TextView,
        val update: TextView,
        val content: LinearLayout,
        val quotaContainer: LinearLayout,
        val insightsColumn: LinearLayout,
        val dailyUsageChart: DailyUsageChartView,
        val metrics: GridLayout,
        var provider: ProviderPresentation,
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: DashboardRepository
    private lateinit var weatherRepository: WeatherRepository
    private lateinit var displayAdapter: DisplayAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val providerBlocks = mutableListOf<ProviderBlock>()
    private var readerState: ReaderState? = null
    private var presentation: DashboardPresentation? = null
    private var adapterAttached = false
    private var activeProviderIndex = 0
    private var focusRemainingMillis = FOCUS_DURATION_MILLIS
    private var focusEndsAtElapsedMillis = 0L
    private var focusRunning = false
    private var weatherSnapshot: WeatherSnapshot? = null
    private var weatherLocationRequestID = 0
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val foregroundScreenWakeLockTag: String
        get() = if (BuildConfig.DISPLAY_KIND == "boox") BOOX_GUEST_WAKE_LOCK_TAG else "$packageName:ink-dashboard-visible"

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        getSharedPreferences(WEATHER_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(PREFERENCE_PRECISE_LOCATION_REQUESTED, true)
            .apply()
        if (
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
        ) {
            refreshWeather()
        } else {
            renderWeatherUnavailable()
        }
    }

    private val refreshLoop = object : Runnable {
        override fun run() {
            refreshSnapshot()
            binding.dashboardRoot.postDelayed(this, REFRESH_INTERVAL_MILLIS)
        }
    }

    private val dashboardTicker = object : Runnable {
        override fun run() {
            renderClockAndFocus()
            scheduleNextDashboardTick()
        }
    }

    private val screenWakeLockRenewal = object : Runnable {
        override fun run() {
            renewForegroundScreenWakeLock()
            handler.postDelayed(this, BOOX_WAKE_LOCK_RENEWAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets()

        repository = DashboardRepository(applicationContext)
        weatherRepository = WeatherRepository(applicationContext)
        displayAdapter = DisplayAdapterFactory.create()
        restoreFocusState(savedInstanceState)
        configureResponsiveLayout()
        binding.dashboardRoot.post {
            if (binding.dashboardRoot.isAttachedToWindow) {
                displayAdapter.attach(binding.dashboardRoot)
                adapterAttached = true
                applyAdaptivePalette()
            }
            sizeProviderPages()
        }

        binding.refreshButton.setOnClickListener { refreshSnapshot() }
        binding.settingsButton.setOnClickListener { showHostDialog() }
        binding.weatherBlock.setOnClickListener { ensureWeatherPermission() }
        binding.focusPlayButton.setOnClickListener { startFocusTimer() }
        binding.focusPauseButton.setOnClickListener { pauseFocusTimer() }
        binding.focusResetButton.setOnClickListener { resetFocusTimer() }
        binding.providerPager.onPageSelected = { index ->
            if (index != activeProviderIndex) {
                activeProviderIndex = index
                updateProviderDots()
                submitDisplayUpdate(SemanticChangeSet(setOf(RegionKey.ProviderList)))
            }
        }

        weatherSnapshot = weatherRepository.cached()
        weatherSnapshot?.let(::renderWeather) ?: renderWeatherUnavailable()
        renderClockAndFocus(force = true)
        render(repository.loadInitial())
        if (savedInstanceState == null) {
            binding.weatherBlock.post { ensureWeatherPermission() }
        }
    }

    override fun onStart() {
        super.onStart()
        binding.dashboardRoot.keepScreenOn = true
        acquireForegroundScreenWakeLock()
        handler.removeCallbacks(screenWakeLockRenewal)
        if (BuildConfig.DISPLAY_KIND == "boox") {
            handler.postDelayed(screenWakeLockRenewal, BOOX_WAKE_LOCK_RENEWAL_MILLIS)
        }
        binding.dashboardRoot.removeCallbacks(refreshLoop)
        binding.dashboardRoot.post(refreshLoop)
        handler.removeCallbacks(dashboardTicker)
        handler.post(dashboardTicker)
        if (hasAnyLocationPermission()) refreshWeather()
    }

    override fun onStop() {
        persistFocusState()
        binding.dashboardRoot.keepScreenOn = false
        handler.removeCallbacks(screenWakeLockRenewal)
        releaseForegroundScreenWakeLock()
        binding.dashboardRoot.removeCallbacks(refreshLoop)
        handler.removeCallbacks(dashboardTicker)
        super.onStop()
    }

    override fun onDestroy() {
        releaseForegroundScreenWakeLock()
        displayAdapter.detach()
        repository.close()
        weatherRepository.close()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireForegroundScreenWakeLock() {
        val wakeLock = screenWakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(
                // Matches BOOX WakeLockHolder.WAKEUP_FLAGS so renewal also resets its standby timer.
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                foregroundScreenWakeLockTag,
            )
            .also {
                it.setReferenceCounted(false)
                screenWakeLock = it
        }
        if (!wakeLock.isHeld) wakeLock.acquire()
        Log.i(TAG, "foreground screen wake lock held=${wakeLock.isHeld}")
    }

    private fun renewForegroundScreenWakeLock() {
        screenWakeLock?.takeIf { it.isHeld }?.release()
        acquireForegroundScreenWakeLock()
        Log.i(TAG, "foreground screen wake lock renewed")
    }

    private fun releaseForegroundScreenWakeLock() {
        screenWakeLock?.takeIf { it.isHeld }?.let {
            it.release()
            Log.i(TAG, "foreground screen wake lock released")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val remaining = currentFocusRemainingMillis()
        outState.putLong(STATE_FOCUS_REMAINING, remaining)
        outState.putBoolean(STATE_FOCUS_RUNNING, focusRunning && remaining > 0)
        outState.putInt(STATE_PROVIDER_INDEX, activeProviderIndex)
    }

    private fun restoreFocusState(savedInstanceState: Bundle?) {
        val preferences = getSharedPreferences(FOCUS_PREFERENCES, MODE_PRIVATE)
        focusRunning = savedInstanceState?.getBoolean(STATE_FOCUS_RUNNING)
            ?: preferences.getBoolean(PREFERENCE_FOCUS_RUNNING, false)
        focusRemainingMillis = if (savedInstanceState != null) {
            savedInstanceState.getLong(STATE_FOCUS_REMAINING, FOCUS_DURATION_MILLIS)
        } else if (focusRunning) {
            FocusTimerMath.remainingMillis(
                running = true,
                endsAtElapsedMillis = preferences.getLong(PREFERENCE_FOCUS_END_WALL_CLOCK, 0L),
                pausedRemainingMillis = FOCUS_DURATION_MILLIS,
                nowElapsedMillis = System.currentTimeMillis(),
            )
        } else {
            preferences.getLong(PREFERENCE_FOCUS_REMAINING, FOCUS_DURATION_MILLIS)
        }
        focusRunning = focusRunning && focusRemainingMillis > 0L
        activeProviderIndex = savedInstanceState?.getInt(STATE_PROVIDER_INDEX, 0) ?: 0
        if (focusRunning && focusRemainingMillis > 0) {
            focusEndsAtElapsedMillis = SystemClock.elapsedRealtime() + focusRemainingMillis
        }
    }

    private fun refreshSnapshot() {
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
        if (state == null) {
            binding.providerPager.visibility = View.GONE
            binding.providerDots.visibility = View.GONE
            binding.emptyStateText.visibility = View.VISIBLE
            binding.emptyStateText.text = result.errorLabel?.let(::localizedErrorLabel)
                ?: getString(R.string.waiting_for_fixture)
            submitDisplayUpdate(SemanticChangeSet.full)
            return
        }

        readerState = state
        val next = DashboardPresenter.present(state, System.currentTimeMillis())
        val changes = DashboardPresenter.diff(presentation, next)
        binding.emptyStateText.setText(R.string.waiting_for_fixture)
        bindPresentation(next)
        presentation = next
        Log.i(
            TAG,
            "render source=${result.sourceLabel} regions=${changes.regions.size} adapter=${displayAdapter.capabilityLabel}",
        )
        submitDisplayUpdate(changes)
    }

    private fun bindPresentation(next: DashboardPresentation) {
        bindProviders(next.providers)
    }

    private fun bindProviders(providers: List<ProviderPresentation>) {
        binding.emptyStateText.visibility = if (providers.isEmpty()) View.VISIBLE else View.GONE
        binding.providerPager.visibility = if (providers.isEmpty()) View.GONE else View.VISIBLE
        binding.providerDots.visibility = if (providers.size > 1) View.VISIBLE else View.INVISIBLE

        val previousProviderID = providerBlocks.getOrNull(activeProviderIndex)?.providerID
        if (providerBlocks.map { it.providerID } != providers.map { it.id }) {
            binding.providerContainer.removeAllViews()
            providerBlocks.clear()
            providers.forEach { provider ->
                val block = createProviderBlock(provider)
                providerBlocks += block
                binding.providerContainer.addView(block.root)
            }
            activeProviderIndex = previousProviderID
                ?.let { id -> providerBlocks.indexOfFirst { it.providerID == id } }
                ?.takeIf { it >= 0 }
                ?: activeProviderIndex.coerceIn(0, (providerBlocks.size - 1).coerceAtLeast(0))
            configureProviderBlocks()
            buildProviderDots()
            binding.providerPager.post {
                sizeProviderPages()
                showProvider(activeProviderIndex)
            }
        } else {
            providers.zip(providerBlocks).forEach { (provider, block) ->
                block.provider = provider
                bindProviderBlock(block)
            }
        }
    }

    private fun createProviderBlock(provider: ProviderPresentation): ProviderBlock {
        val root = layoutInflater.inflate(
            R.layout.provider_dashboard_card,
            binding.providerContainer,
            false,
        )
        return ProviderBlock(
            providerID = provider.id,
            root = root,
            name = root.findViewById(R.id.provider_name),
            subtitle = root.findViewById(R.id.provider_subtitle),
            update = root.findViewById(R.id.provider_update),
            content = root.findViewById(R.id.provider_content),
            quotaContainer = root.findViewById(R.id.quota_container),
            insightsColumn = root.findViewById(R.id.insights_column),
            dailyUsageChart = root.findViewById(R.id.daily_usage_chart),
            metrics = root.findViewById(R.id.provider_metrics),
            provider = provider,
        ).also(::bindProviderBlock)
    }

    private fun bindProviderBlock(block: ProviderBlock) {
        val provider = block.provider
        val accentColor = providerAccentColor(provider.id)
        block.name.text = provider.name
        block.name.setTextColor(accentColor)
        block.subtitle.text = provider.subtitle.ifBlank { provider.status }
        block.subtitle.visibility = if (block.subtitle.text.isBlank()) View.GONE else View.VISIBLE
        block.update.text = provider.update
        block.update.visibility = if (provider.update.isBlank()) View.INVISIBLE else View.VISIBLE
        bindQuotas(block, provider, accentColor)
        val history = provider.usageHistory
        block.dailyUsageChart.setAccentColor(accentColor)
        block.dailyUsageChart.bind(
            title = when (history?.kind) {
                UsageHistoryKind.COST -> getString(R.string.daily_cost)
                UsageHistoryKind.TOKENS -> getString(R.string.daily_tokens)
                null -> getString(R.string.daily_usage)
            },
            emptyLabel = getString(R.string.usage_history_unavailable),
            kind = history?.kind,
            points = history?.points.orEmpty(),
        )
        bindMetrics(block)
        block.root.contentDescription = buildString {
            append(provider.name).append(". ")
            provider.quotas.forEach { quota ->
                append(quota.label).append(", ").append(quota.remaining).append(" left. ")
            }
            provider.metrics.forEach { metric ->
                append(metric.label).append(", ").append(metric.value).append(". ")
            }
        }
    }

    private fun bindQuotas(
        block: ProviderBlock,
        provider: ProviderPresentation,
        accentColor: Int,
    ) {
        block.quotaContainer.removeAllViews()
        val quotas = provider.quotas.ifEmpty {
            listOf(
                QuotaPresentation(
                    kind = "primary",
                    label = provider.primary,
                    remaining = provider.remaining.removeSuffix(" LEFT"),
                    reset = provider.reset.removePrefix("RESETS "),
                    remainingPercent = provider.remainingPercent,
                ),
            )
        }.sortedBy(::quotaPriority)
        val profile = layoutProfile()
        val horizontal = profile.horizontalProviderContent
        quotas.forEach { quota ->
            val root = layoutInflater.inflate(
                R.layout.quota_dashboard_item,
                block.quotaContainer,
                false,
            )
            root.layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(if (profile.compactPhoneChrome) 96 else 112),
                )
            }
            root.findViewById<TextView>(R.id.quota_label).text = quota.label
            root.findViewById<TextView>(R.id.quota_label).setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (profile.compactPhoneChrome) 12f else 15f,
            )
            root.findViewById<TextView>(R.id.quota_remaining).apply {
                text = quota.remaining
                setTextColor(accentColor)
                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    if (profile.compactPhoneChrome) 18f else 21f,
                )
            }
            root.findViewById<TextView>(R.id.quota_reset).apply {
                text = quota.reset
                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    if (profile.compactPhoneChrome) 11f else 14f,
                )
                visibility = if (
                    quota.reset == "—" ||
                    quota.reset == "RESET —" ||
                    quota.reset.isBlank()
                ) {
                    View.INVISIBLE
                } else {
                    View.VISIBLE
                }
            }
            root.findViewById<ProgressBar>(R.id.quota_progress).apply {
                tintProgressBar(this, accentColor)
                setProgress(quota.remainingPercent ?: 0, false)
                visibility = if (quota.remainingPercent == null) View.INVISIBLE else View.VISIBLE
            }
            block.quotaContainer.addView(root)
        }
    }

    private fun bindMetrics(block: ProviderBlock) {
        val profile = layoutProfile()
        val columnCount = profile.metricColumns
        val allMetrics = block.provider.metrics.ifEmpty {
            listOf(MetricPresentation(label = "STATUS", value = block.provider.status))
        }
        val metrics = allMetrics
        block.metrics.removeAllViews()
        block.metrics.columnCount = columnCount
        block.metrics.rowCount = ceil(metrics.size.toDouble() / columnCount).toInt().coerceAtLeast(1)
        metrics.forEachIndexed { index, metric ->
            val root = layoutInflater.inflate(R.layout.metric_dashboard_item, block.metrics, false)
            root.findViewById<TextView>(R.id.metric_label).apply {
                text = metric.label
                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    if (profile.compactPhoneChrome) 10f else 13f,
                )
            }
            root.findViewById<TextView>(R.id.metric_value).apply {
                text = metric.value
                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    if (profile.compactPhoneChrome) 12f else 15f,
                )
            }
            root.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(index / columnCount),
                GridLayout.spec(index % columnCount, 1f),
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            block.metrics.addView(root)
        }
    }

    private fun configureResponsiveLayout() {
        val profile = layoutProfile()
        val compact = profile.compactPhoneChrome
        val compactLandscape = compact && profile.horizontalProviderContent
        binding.headerRegion.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(
                when {
                    compactLandscape -> 88
                    compact -> 150
                    else -> 142
                },
            ),
        )
        binding.usageRegion.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
        binding.focusRegion.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(
                when {
                    compactLandscape -> 76
                    compact -> 108
                    else -> 102
                },
            ),
        )
        binding.headerRegion.orientation = LinearLayout.HORIZONTAL
        binding.timeBlock.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (compact) 0.82f else 1f,
        )
        binding.deviceSummary.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (compact) 1.48f else 1f,
        )
        binding.deviceSummary.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        binding.weatherBlock.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        )
        binding.weatherBlock.gravity = Gravity.END
        binding.weatherBlock.setPadding(
            dp(if (compact) 4 else 12),
            0,
            dp(if (compact) 8 else 18),
            0,
        )
        binding.headerRegion.setPadding(
            dp(if (compact) 12 else 24),
            dp(if (compact) 10 else 14),
            dp(if (compact) 12 else 24),
            dp(if (compact) 10 else 14),
        )
        binding.currentTimeText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            when {
                compactLandscape -> 30f
                compact -> 36f
                else -> 46f
            },
        )
        binding.currentDateText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (compactLandscape) 10f else if (compact) 11f else 14f,
        )
        binding.weatherTemperatureText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (compactLandscape) 20f else if (compact) 23f else 28f,
        )
        binding.weatherDetailText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (compactLandscape) 10f else if (compact) 11f else 14f,
        )
        binding.weatherAttributionText.visibility = if (compact) View.GONE else View.VISIBLE
        configureIconButton(binding.refreshButton, if (compact) 42 else 48, if (compact) 10 else 12)
        configureIconButton(binding.settingsButton, if (compact) 42 else 48, if (compact) 10 else 12)

        binding.focusRegion.orientation = LinearLayout.HORIZONTAL
        binding.focusRegion.setPadding(
            dp(if (compact) 12 else 24),
            dp(10),
            dp(if (compact) 12 else 24),
            dp(10),
        )
        binding.focusSummary.layoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        binding.focusControls.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        binding.focusControls.gravity = Gravity.CENTER_VERTICAL
        binding.focusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 15f)
        binding.focusMinutesText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (compactLandscape) 32f else if (compact) 38f else 48f,
        )
        binding.focusUnit.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 15f)
        configureIconButton(binding.focusPlayButton, if (compact) 44 else 50, if (compact) 12 else 14)
        configureIconButton(binding.focusPauseButton, if (compact) 44 else 50, if (compact) 12 else 14)
        configureIconButton(binding.focusResetButton, if (compact) 44 else 50, if (compact) 12 else 14)
        binding.usageRegion.setPadding(
            dp(if (compact) 12 else 24),
            dp(if (compactLandscape) 6 else if (compact) 10 else 14),
            dp(if (compact) 12 else 24),
            dp(4),
        )
        configureProviderBlocks()
    }

    private fun configureProviderBlocks() {
        val profile = layoutProfile()
        val horizontal = profile.horizontalProviderContent
        providerBlocks.forEach { block ->
            block.name.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (profile.compactPhoneChrome) 16f else 19f,
            )
            block.subtitle.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (profile.compactPhoneChrome) 11f else 14f,
            )
            block.update.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (profile.compactPhoneChrome) 11f else 14f,
            )
            block.content.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            block.insightsColumn.orientation =
                if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            block.quotaContainer.layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (profile.readerChrome) 0.86f else 0.92f,
                )
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            block.insightsColumn.layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (profile.readerChrome) 1.14f else 1.08f,
                ).apply {
                    marginStart = dp(if (profile.compactPhoneChrome) 12 else 18)
                }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.82f).apply {
                    topMargin = dp(10)
                }
            }
            block.root.findViewById<View>(R.id.insights_divider).layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            }
            block.dailyUsageChart.layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (profile.readerChrome) 0.88f else 1.05f,
                ).apply {
                    marginStart = dp(10)
                }
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            }
            block.metrics.layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (profile.readerChrome) 1.12f else 0.95f,
                ).apply {
                    marginStart = dp(12)
                }
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            block.dailyUsageChart.setCompactMode(profile.compactPhoneChrome)
            bindMetrics(block)
        }
    }

    private fun configureIconButton(button: ImageButton, sizeDp: Int, paddingDp: Int) {
        button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
            width = dp(sizeDp)
            height = dp(sizeDp)
        }
        button.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
    }

    private fun quotaPriority(quota: QuotaPresentation): Int = when {
        quota.kind == "weekly" -> 0
        quota.kind == "codex-spark-weekly" -> 1
        quota.kind.contains("weekly", ignoreCase = true) -> 2
        else -> 3
    }

    private fun layoutProfile(): DashboardLayoutProfile {
        val configuration = resources.configuration
        return DashboardLayoutPolicy.resolve(
            widthDp = configuration.screenWidthDp,
            heightDp = configuration.screenHeightDp,
            smallestWidthDp = configuration.smallestScreenWidthDp,
        )
    }

    private fun sizeProviderPages() {
        val pageWidth = binding.providerPager.width
        if (pageWidth <= 0) return
        providerBlocks.forEach { block ->
            block.root.layoutParams = LinearLayout.LayoutParams(
                pageWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        binding.providerContainer.requestLayout()
        binding.providerPager.pageCount = providerBlocks.size
        binding.providerPager.showPage(activeProviderIndex, animated = false)
    }

    private fun buildProviderDots() {
        binding.providerDots.removeAllViews()
        providerBlocks.forEachIndexed { index, block ->
            val dot = ImageButton(this).apply {
                background = null
                setImageResource(if (index == activeProviderIndex) R.drawable.dot_active else R.drawable.dot_inactive)
                contentDescription = getString(
                    R.string.provider_page,
                    block.provider.name,
                    index + 1,
                    providerBlocks.size,
                )
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { showProvider(index) }
            }
            binding.providerDots.addView(
                dot,
                LinearLayout.LayoutParams(dp(40), dp(32)),
            )
        }
    }

    private fun showProvider(index: Int) {
        if (providerBlocks.isEmpty()) return
        activeProviderIndex = index.coerceIn(providerBlocks.indices)
        binding.providerPager.showPage(activeProviderIndex, animated = true)
        updateProviderDots()
        submitDisplayUpdate(SemanticChangeSet(setOf(RegionKey.ProviderList)))
    }

    private fun updateProviderDots() {
        val activeColor = providerBlocks.getOrNull(activeProviderIndex)
            ?.let { providerAccentColor(it.providerID) }
            ?: getColor(R.color.ink_black)
        for (index in 0 until binding.providerDots.childCount) {
            val dot = binding.providerDots.getChildAt(index) as? ImageButton ?: continue
            dot.setImageResource(if (index == activeProviderIndex) R.drawable.dot_active else R.drawable.dot_inactive)
            dot.imageTintList = ColorStateList.valueOf(
                if (index == activeProviderIndex) activeColor else getColor(R.color.ink_light),
            )
            dot.isSelected = index == activeProviderIndex
        }
    }

    private fun startFocusTimer() {
        if (currentFocusRemainingMillis() <= 0) {
            focusRemainingMillis = FOCUS_DURATION_MILLIS
        }
        if (!focusRunning) {
            focusRunning = true
            focusEndsAtElapsedMillis = SystemClock.elapsedRealtime() + focusRemainingMillis
            persistFocusState()
            renderClockAndFocus(force = true)
            restartDashboardTicker()
        }
    }

    private fun pauseFocusTimer() {
        if (!focusRunning) return
        focusRemainingMillis = currentFocusRemainingMillis()
        focusRunning = false
        persistFocusState()
        renderClockAndFocus(force = true)
        restartDashboardTicker()
    }

    private fun resetFocusTimer() {
        focusRunning = false
        focusRemainingMillis = FOCUS_DURATION_MILLIS
        focusEndsAtElapsedMillis = 0L
        persistFocusState()
        renderClockAndFocus(force = true)
        restartDashboardTicker()
    }

    private fun restartDashboardTicker() {
        handler.removeCallbacks(dashboardTicker)
        scheduleNextDashboardTick()
    }

    private fun scheduleNextDashboardTick() {
        handler.postDelayed(
            dashboardTicker,
            FocusTimerMath.nextTickDelayMillis(
                running = focusRunning,
                nowWallClockMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun persistFocusState() {
        val remaining = currentFocusRemainingMillis()
        getSharedPreferences(FOCUS_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(PREFERENCE_FOCUS_RUNNING, focusRunning && remaining > 0L)
            .putLong(PREFERENCE_FOCUS_REMAINING, remaining)
            .putLong(
                PREFERENCE_FOCUS_END_WALL_CLOCK,
                if (focusRunning && remaining > 0L) {
                    System.currentTimeMillis() + remaining
                } else {
                    0L
                },
            )
            .apply()
    }

    private fun currentFocusRemainingMillis(): Long {
        return FocusTimerMath.remainingMillis(
            running = focusRunning,
            endsAtElapsedMillis = focusEndsAtElapsedMillis,
            pausedRemainingMillis = focusRemainingMillis,
            nowElapsedMillis = SystemClock.elapsedRealtime(),
        )
    }

    private fun renderClockAndFocus(force: Boolean = false) {
        val now = ZonedDateTime.now()
        val time = TIME_FORMATTER.format(now)
        val date = DATE_FORMATTER.format(now).uppercase(Locale.getDefault())
        val remaining = currentFocusRemainingMillis()
        if (focusRunning && remaining == 0L) {
            focusRunning = false
            focusRemainingMillis = 0L
            persistFocusState()
        }
        val focusTime = FocusTimerMath.formatRemaining(remaining)
        val headerChanged = force ||
            binding.currentTimeText.text.toString() != time ||
            binding.currentDateText.text.toString() != date
        val focusChanged = force || binding.focusMinutesText.text.toString() != focusTime

        if (headerChanged) {
            binding.currentTimeText.text = time
            binding.currentDateText.text = date
        }
        if (focusChanged) {
            binding.focusMinutesText.text = focusTime
            binding.focusPlayButton.isEnabled = !focusRunning
            binding.focusPauseButton.isEnabled = focusRunning
            renderFocusButtonStyles()
        }
        val changedRegions = buildSet {
            if (headerChanged) add(RegionKey.Header)
            if (focusChanged) add(RegionKey.Focus)
        }
        submitDisplayUpdate(SemanticChangeSet(changedRegions))
    }

    private fun renderFocusButtonStyles() {
        val primaryTint = ColorStateList.valueOf(getColor(R.color.paper))
        val secondaryTint = ColorStateList.valueOf(getColor(R.color.ink_black))
        binding.focusPlayButton.setBackgroundResource(
            if (focusRunning) R.drawable.icon_button_ink else R.drawable.button_ink,
        )
        binding.focusPlayButton.imageTintList = if (focusRunning) secondaryTint else primaryTint
        binding.focusPauseButton.setBackgroundResource(
            if (focusRunning) R.drawable.button_ink else R.drawable.icon_button_ink,
        )
        binding.focusPauseButton.imageTintList = if (focusRunning) primaryTint else secondaryTint
    }

    private fun ensureWeatherPermission() {
        val preciseWasRequested = getSharedPreferences(WEATHER_PREFERENCES, MODE_PRIVATE)
            .getBoolean(PREFERENCE_PRECISE_LOCATION_REQUESTED, false)
        if (hasFineLocationPermission() || hasAnyLocationPermission() && preciseWasRequested) {
            refreshWeather()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasAnyLocationPermission(): Boolean =
        hasFineLocationPermission() ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun refreshWeather() {
        if (!hasAnyLocationPermission()) {
            renderWeatherUnavailable()
            return
        }
        binding.weatherDetailText.setText(R.string.weather_locating)
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val networkEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        val providers = WeatherLocationPolicy.providers(
            fineLocationGranted = hasFineLocationPermission(),
            gpsEnabled = gpsEnabled,
            networkEnabled = networkEnabled,
        )
        val fallback = providers.firstNotNullOfOrNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }
        requestWeatherLocation(
            locationManager = locationManager,
            providers = providers,
            index = 0,
            fallback = fallback,
            requestID = ++weatherLocationRequestID,
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestWeatherLocation(
        locationManager: LocationManager,
        providers: List<String>,
        index: Int,
        fallback: Location?,
        requestID: Int,
    ) {
        if (requestID != weatherLocationRequestID) return
        val provider = providers.getOrNull(index)
        if (provider == null) {
            useWeatherLocation(fallback)
            return
        }
        val cancellation = CancellationSignal()
        var completed = false
        val timeout = Runnable {
            if (completed || requestID != weatherLocationRequestID) return@Runnable
            completed = true
            cancellation.cancel()
            requestWeatherLocation(locationManager, providers, index + 1, fallback, requestID)
        }
        handler.postDelayed(timeout, LOCATION_PROVIDER_TIMEOUT_MILLIS)
        runCatching {
            locationManager.getCurrentLocation(provider, cancellation, mainExecutor) { location ->
                if (completed || requestID != weatherLocationRequestID) return@getCurrentLocation
                completed = true
                handler.removeCallbacks(timeout)
                if (location != null) {
                    useWeatherLocation(location)
                } else {
                    requestWeatherLocation(locationManager, providers, index + 1, fallback, requestID)
                }
            }
        }.onFailure {
            if (!completed) {
                completed = true
                handler.removeCallbacks(timeout)
                requestWeatherLocation(locationManager, providers, index + 1, fallback, requestID)
            }
        }
    }

    private fun useWeatherLocation(location: Location?) {
        if (location == null) {
            refreshWeatherFromNetwork()
            return
        }
        binding.weatherDetailText.setText(R.string.weather_loading)
        weatherRepository.refresh(location.latitude, location.longitude) { snapshot, error ->
            if (snapshot != null) {
                weatherSnapshot = snapshot
                renderWeather(snapshot)
            } else if (error != null) {
                renderWeatherUnavailable(showRetry = true)
            }
        }
    }

    private fun refreshWeatherFromNetwork() {
        binding.weatherDetailText.setText(R.string.weather_loading)
        weatherRepository.refreshFromNetworkLocation { snapshot, error ->
            if (snapshot != null) {
                weatherSnapshot = snapshot
                renderWeather(snapshot)
            } else if (error != null) {
                renderWeatherUnavailable(showRetry = true)
            }
        }
    }

    private fun renderWeather(snapshot: WeatherSnapshot) {
        val description = weatherDescription(snapshot.weatherCode)
        binding.weatherTemperatureText.text = getString(
            R.string.weather_temperature_value,
            snapshot.temperatureCelsius,
        )
        binding.weatherDetailText.text = getString(
            R.string.weather_details,
            snapshot.humidityPercent,
            snapshot.windSpeedKmh,
        )
        binding.weatherBlock.contentDescription = getString(
            R.string.weather_description,
            description,
            snapshot.temperatureCelsius,
            snapshot.humidityPercent,
            snapshot.windSpeedKmh,
        )
        submitDisplayUpdate(SemanticChangeSet(setOf(RegionKey.Header)))
    }

    private fun renderWeatherUnavailable(showRetry: Boolean = false) {
        binding.weatherTemperatureText.setText(R.string.weather_temperature_unknown)
        binding.weatherDetailText.setText(
            if (showRetry) R.string.weather_unavailable else R.string.weather_enable,
        )
        binding.weatherBlock.contentDescription = getString(R.string.weather_enable)
    }

    private fun weatherDescription(code: Int): String = getString(
        when (code) {
            0 -> R.string.weather_clear
            1, 2, 3 -> R.string.weather_cloudy
            45, 48 -> R.string.weather_fog
            in 51..67, in 80..82 -> R.string.weather_rain
            in 71..77, 85, 86 -> R.string.weather_snow
            in 95..99 -> R.string.weather_storm
            else -> R.string.weather_unknown
        },
    )

    private fun localizedErrorLabel(raw: String): String = when (raw) {
        "Open settings" -> getString(R.string.open_settings)
        "Usage Host timed out", "Usage Host temporarily unavailable" ->
            getString(R.string.source_network_failed)
        else -> raw
    }

    private fun applyAdaptivePalette() {
        providerBlocks.forEach(::bindProviderBlock)
        updateProviderDots()
        Log.i(
            TAG,
            "display palette color=${displayAdapter.supportsColor} adapter=${displayAdapter.capabilityLabel}",
        )
    }

    private fun providerAccentColor(providerID: String): Int = getColor(
        when (DisplayPalette.accent(providerID, displayAdapter.supportsColor)) {
            DisplayAccent.INK -> R.color.ink_black
            DisplayAccent.BLUE -> R.color.accent_codex
            DisplayAccent.VIOLET -> R.color.accent_claude
        },
    )

    private fun tintProgressBar(progressBar: ProgressBar, accentColor: Int) {
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(getColor(R.color.ink_light))
        }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accentColor)
        }
        val clippedFill = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)
        progressBar.progressDrawable = LayerDrawable(arrayOf(background, clippedFill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun submitDisplayUpdate(changes: SemanticChangeSet) {
        if (changes.isEmpty) return
        binding.dashboardRoot.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
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
            },
        )
        binding.dashboardRoot.invalidate()
    }

    private fun regionRegistry(): Map<RegionKey, View> = buildMap {
        put(RegionKey.Root, binding.dashboardRoot)
        put(RegionKey.Header, binding.headerRegion)
        put(RegionKey.Focus, binding.focusRegion)
        put(RegionKey.ProviderList, binding.providerPager)
        providerBlocks.forEach { block ->
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "CodexBarInk"
        private const val BOOX_GUEST_WAKE_LOCK_TAG = "onyx_framework"
        private const val BOOX_WAKE_LOCK_RENEWAL_MILLIS = 90_000L
        const val REFRESH_INTERVAL_MILLIS = 5 * 60 * 1_000L
        const val FOCUS_DURATION_MILLIS = 25 * 60 * 1_000L
        const val FOCUS_PREFERENCES = "focus_timer"
        const val WEATHER_PREFERENCES = "weather_permissions"
        const val PREFERENCE_FOCUS_RUNNING = "running"
        const val PREFERENCE_FOCUS_REMAINING = "remaining"
        const val PREFERENCE_FOCUS_END_WALL_CLOCK = "end_wall_clock"
        const val PREFERENCE_PRECISE_LOCATION_REQUESTED = "precise_location_requested"
        const val LOCATION_PROVIDER_TIMEOUT_MILLIS = 5_000L
        const val STATE_FOCUS_REMAINING = "focus_remaining"
        const val STATE_FOCUS_RUNNING = "focus_running"
        const val STATE_PROVIDER_INDEX = "provider_index"
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd EEE")
    }
}
