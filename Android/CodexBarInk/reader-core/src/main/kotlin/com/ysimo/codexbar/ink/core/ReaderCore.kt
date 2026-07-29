package com.ysimo.codexbar.ink.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class ReaderState(
    val schemaVersion: Int,
    val generatedAt: String,
    val staleAfterSeconds: Long,
    val receivedAtEpochMillis: Long,
    val providers: List<ProviderState>,
)

data class ProviderState(
    val id: String,
    val name: String,
    val sortKey: Int,
    val source: String?,
    val windows: List<WindowState>,
    val credits: CreditState?,
    val todayCostUSD: Double?,
    val last30DaysCostUSD: Double?,
    val dailyUsage: List<DailyUsageState>?,
    val plan: String?,
    val statusLabel: String?,
    val errorMessage: String?,
    val errorReason: String?,
    val updatedAt: String?,
)

data class DailyUsageState(
    val date: String,
    val costUSD: Double?,
    val totalTokens: Int?,
)

data class WindowState(
    val kind: String,
    val label: String,
    val usedPercent: Double?,
    val remainingPercent: Double?,
    val resetAt: String?,
)

data class CreditState(
    val remaining: Double,
    val unit: String,
)

data class DashboardPresentation(
    val freshness: String,
    val generatedAt: String,
    val providers: List<ProviderPresentation>,
    val codex: ProviderPresentation?,
    val claude: ProviderPresentation?,
    val genericProviders: List<ProviderPresentation>,
)

data class QuotaPresentation(
    val kind: String,
    val label: String,
    val remaining: String,
    val reset: String,
    val remainingPercent: Int?,
)

data class MetricPresentation(
    val label: String,
    val value: String,
)

enum class UsageHistoryKind {
    COST,
    TOKENS,
}

data class UsageHistoryPoint(
    val date: String,
    val value: Double,
)

data class UsageHistoryPresentation(
    val kind: UsageHistoryKind,
    val points: List<UsageHistoryPoint>,
)

data class ProviderPresentation(
    val id: String,
    val name: String,
    val remaining: String,
    val primary: String,
    val reset: String,
    val secondary: String,
    val status: String,
    val remainingPercent: Int?,
    val subtitle: String,
    val update: String,
    val quotas: List<QuotaPresentation>,
    val metrics: List<MetricPresentation>,
    val usageHistory: UsageHistoryPresentation?,
)

sealed interface RegionKey {
    data object Header : RegionKey
    data object Focus : RegionKey
    data class Provider(val id: String) : RegionKey
    data object ProviderList : RegionKey
    data object Root : RegionKey
}

data class SemanticChangeSet(val regions: Set<RegionKey>) {
    val isEmpty: Boolean
        get() = this.regions.isEmpty()

    companion object {
        val none = SemanticChangeSet(emptySet())
        val full = SemanticChangeSet(setOf(RegionKey.Root))
    }
}

object ReaderReducer {
    private val gson = Gson()

    fun decodeAndMerge(rawJson: String, previous: ReaderState?, receivedAtEpochMillis: Long): ReaderState {
        val root = JsonParser.parseString(rawJson).asJsonObject
        require(root.int("schemaVersion") == 1) { "Unsupported dashboard schema" }
        val generatedAt = root.string("generatedAt") ?: error("Missing generatedAt")
        val staleAfterSeconds = root.long("staleAfterSeconds") ?: error("Missing staleAfterSeconds")
        require(staleAfterSeconds > 0) { "Invalid staleAfterSeconds" }
        val previousByID = previous?.providers?.associateBy { it.id }.orEmpty()
        val incoming = root.getAsJsonArray("providers") ?: error("Missing providers")

        val providers = incoming.mapNotNull { element ->
            val provider = element.asJsonObject
            if (provider.boolean("enabled") == false) return@mapNotNull null
            val id = provider.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            sanitizeProvider(provider, previousByID[id])
        }.sortedWith(compareBy<ProviderState> { it.sortKey }.thenBy { it.id })

        return ReaderState(
            schemaVersion = 1,
            generatedAt = generatedAt,
            staleAfterSeconds = staleAfterSeconds,
            receivedAtEpochMillis = receivedAtEpochMillis,
            providers = providers,
        )
    }

    fun encode(state: ReaderState): String = this.gson.toJson(state)

    fun decodeStored(rawJson: String): ReaderState = this.gson.fromJson(rawJson, ReaderState::class.java)

    private fun sanitizeProvider(provider: JsonObject, previous: ProviderState?): ProviderState {
        val id = provider.string("id") ?: error("Provider id disappeared")
        val windows = provider.getAsJsonArray("windows")?.mapNotNull { element ->
            val window = element.asJsonObject
            val label = window.string("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WindowState(
                kind = window.string("kind") ?: "unknown",
                label = label,
                usedPercent = window.double("usedPercent")?.coerceIn(0.0, 100.0),
                remainingPercent = window.double("remainingPercent")?.coerceIn(0.0, 100.0),
                resetAt = window.string("resetAt"),
            )
        }.orEmpty()

        val error = provider.objectOrNull("error")
        val credits = provider.objectOrNull("credits")?.let { value ->
            val remaining = value.double("remaining") ?: return@let null
            CreditState(remaining = remaining, unit = value.string("unit") ?: "credits")
        }
        val cost = provider.objectOrNull("cost")
        val dailyUsage = cost?.getAsJsonArray("daily")?.mapNotNull { element ->
            val point = element.asJsonObject
            val date = point.string("date")?.takeIf { it.length >= 10 } ?: return@mapNotNull null
            val costUSD = point.double("costUSD")?.takeIf { it.isFinite() && it >= 0 }
            val totalTokens = point.int("totalTokens")?.takeIf { it >= 0 }
            if (costUSD == null && totalTokens == null) return@mapNotNull null
            DailyUsageState(
                date = date.take(10),
                costUSD = costUSD,
                totalTokens = totalTokens,
            )
        }.orEmpty()
        val display = provider.objectOrNull("display")
        val identity = provider.objectOrNull("identity")
        val status = provider.objectOrNull("status")
        val hasUsableValues = windows.isNotEmpty() ||
            credits != null ||
            cost?.double("todayUSD") != null ||
            cost?.double("last30DaysUSD") != null ||
            dailyUsage.isNotEmpty()
        val canReusePreviousValues = error == null && !hasUsableValues

        return ProviderState(
            id = id,
            name = provider.string("name")?.takeIf { it.isNotBlank() } ?: previous?.name ?: id,
            sortKey = display?.int("sortKey") ?: previous?.sortKey ?: Int.MAX_VALUE,
            source = provider.string("source") ?: if (error == null) previous?.source else null,
            windows = if (canReusePreviousValues) previous?.windows.orEmpty() else windows,
            credits = credits ?: if (canReusePreviousValues) previous?.credits else null,
            todayCostUSD = cost?.double("todayUSD")
                ?: if (canReusePreviousValues) previous?.todayCostUSD else null,
            last30DaysCostUSD = cost?.double("last30DaysUSD")
                ?: if (canReusePreviousValues) previous?.last30DaysCostUSD else null,
            dailyUsage = if (canReusePreviousValues) previous?.dailyUsage.orEmpty() else dailyUsage,
            plan = if (error == null) identity?.string("plan") ?: previous?.plan else null,
            statusLabel = if (error == null) status?.string("label") ?: previous?.statusLabel else null,
            errorMessage = error?.string("message"),
            errorReason = error?.string("reason"),
            updatedAt = provider.string("updatedAt") ?: if (error == null) previous?.updatedAt else null,
        )
    }
}

object DashboardPresenter {
    fun present(
        state: ReaderState,
        nowEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DashboardPresentation {
        val generatedAtMillis = runCatching { Instant.parse(state.generatedAt).toEpochMilli() }
            .getOrDefault(state.receivedAtEpochMillis)
        val staleAt = generatedAtMillis + state.staleAfterSeconds * 1_000
        val freshness = if (nowEpochMillis > staleAt) "STALE · showing last good" else "FRESH · snapshot"
        val cards = state.providers
            .filter { it.errorMessage == null }
            .map { presentProvider(it, nowEpochMillis, zoneId) }

        return DashboardPresentation(
            freshness = freshness,
            generatedAt = state.generatedAt,
            providers = cards,
            codex = cards.firstOrNull { it.id == "codex" },
            claude = cards.firstOrNull { it.id == "claude" },
            genericProviders = cards.filterNot { it.id == "codex" || it.id == "claude" },
        )
    }

    fun diff(previous: DashboardPresentation?, current: DashboardPresentation): SemanticChangeSet {
        if (previous == null) return SemanticChangeSet.full
        val changes = linkedSetOf<RegionKey>()
        if (previous.freshness != current.freshness || previous.generatedAt != current.generatedAt) {
            changes += RegionKey.Header
        }
        if (previous.providers.map { it.id } != current.providers.map { it.id }) {
            changes += RegionKey.ProviderList
        } else {
            current.providers.forEachIndexed { index, provider ->
                if (previous.providers[index] != provider) changes += RegionKey.Provider(provider.id)
            }
        }
        return SemanticChangeSet(changes)
    }

    private fun presentProvider(
        provider: ProviderState,
        nowEpochMillis: Long,
        zoneId: ZoneId,
    ): ProviderPresentation {
        val primaryWindow = provider.windows.firstOrNull()
        val primaryUsedPercent = primaryWindow?.usedPercent
            ?: primaryWindow?.remainingPercent?.let { 100.0 - it }
        val primaryRemainingPercent = primaryWindow?.remainingPercent
            ?: primaryUsedPercent?.let { 100.0 - it }
        val remaining = when {
            primaryRemainingPercent != null -> "${formatPercent(primaryRemainingPercent)} LEFT"
            provider.credits != null ->
                "${formatNumber(provider.credits.remaining)} ${provider.credits.unit.uppercase(Locale.ROOT)} LEFT"
            provider.todayCostUSD != null -> "$${formatCurrency(provider.todayCostUSD)} TODAY"
            provider.last30DaysCostUSD != null -> "$${formatCurrency(provider.last30DaysCostUSD)} / 30D"
            else -> "NO QUOTA"
        }
        val primary = when {
            primaryWindow != null ->
                "${primaryWindow.label.uppercase(Locale.ROOT)} · " +
                    "${primaryUsedPercent?.let(::formatPercent) ?: "—"} USED"
            provider.credits != null -> "CREDITS"
            provider.todayCostUSD != null -> "TODAY'S COST"
            provider.last30DaysCostUSD != null -> "LAST 30 DAYS"
            else -> "No quota data"
        }
        val secondary = buildList {
            provider.windows.drop(1).forEach { window ->
                val windowRemaining = window.remainingPercent
                    ?: window.usedPercent?.let { 100.0 - it }
                add("${window.label} ${windowRemaining?.let(::formatPercent) ?: "—"} left")
            }
            provider.credits?.let { add("${formatNumber(it.remaining)} ${it.unit}") }
            provider.todayCostUSD?.let { add("$${formatCurrency(it)} today") }
            provider.last30DaysCostUSD?.let { add("$${formatCurrency(it)} / 30d") }
        }.joinToString(" · ").ifBlank {
            "No additional windows"
        }
        val statusBase = provider.statusLabel ?: "Available"
        val status = listOfNotNull(statusBase, provider.plan).joinToString(" · ")
        val quotas = provider.windows.take(3).map { window ->
            val usedPercent = window.usedPercent
                ?: window.remainingPercent?.let { 100.0 - it }
            val remainingPercent = window.remainingPercent
                ?: usedPercent?.let { 100.0 - it }
            QuotaPresentation(
                kind = window.kind,
                label = window.label,
                remaining = remainingPercent?.let(::formatPercent) ?: "—",
                reset = formatReset(window.resetAt, zoneId).removePrefix("RESETS "),
                remainingPercent = remainingPercent?.roundToInt()?.coerceIn(0, 100),
            )
        }
        val metrics = buildList {
            provider.todayCostUSD?.let {
                add(MetricPresentation(label = "TODAY", value = "$${formatCurrency(it)}"))
            }
            provider.last30DaysCostUSD?.let {
                add(MetricPresentation(label = "LAST 30 DAYS", value = "$${formatCurrency(it)}"))
            }
            provider.credits?.let {
                add(
                    MetricPresentation(
                        label = "CREDITS",
                        value = "${formatNumber(it.remaining)} ${it.unit}",
                    ),
                )
            }
            provider.plan?.let { add(MetricPresentation(label = "PLAN", value = it)) }
            provider.statusLabel?.let { add(MetricPresentation(label = "STATUS", value = it)) }
            provider.source?.let {
                add(MetricPresentation(label = "SOURCE", value = it.uppercase(Locale.ROOT)))
            }
        }
        val subtitle = listOfNotNull(provider.plan, provider.statusLabel).joinToString(" · ")
        val update = listOfNotNull(
            formatUpdatedAt(provider.updatedAt, nowEpochMillis),
            provider.source?.uppercase(Locale.ROOT),
        ).joinToString(" · ")
        val costHistory = provider.dailyUsage.orEmpty().mapNotNull { point ->
            point.costUSD?.let { UsageHistoryPoint(date = point.date, value = it) }
        }
        val tokenHistory = provider.dailyUsage.orEmpty().mapNotNull { point ->
            point.totalTokens?.let { UsageHistoryPoint(date = point.date, value = it.toDouble()) }
        }
        val usageHistory = when {
            costHistory.isNotEmpty() -> UsageHistoryPresentation(
                kind = UsageHistoryKind.COST,
                points = costHistory.takeLast(14),
            )
            tokenHistory.isNotEmpty() -> UsageHistoryPresentation(
                kind = UsageHistoryKind.TOKENS,
                points = tokenHistory.takeLast(14),
            )
            else -> null
        }
        return ProviderPresentation(
            id = provider.id,
            name = provider.name,
            remaining = remaining,
            primary = primary,
            reset = formatReset(primaryWindow?.resetAt, zoneId),
            secondary = secondary,
            status = status,
            remainingPercent = primaryRemainingPercent?.roundToInt()?.coerceIn(0, 100),
            subtitle = subtitle,
            update = update,
            quotas = quotas,
            metrics = metrics,
            usageHistory = usageHistory,
        )
    }

    private fun formatUpdatedAt(raw: String?, nowEpochMillis: Long): String? {
        val updatedAt = raw?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return null
        val elapsedMinutes = ((nowEpochMillis - updatedAt).coerceAtLeast(0) / 60_000)
        return when {
            elapsedMinutes < 1 -> "JUST UPDATED"
            elapsedMinutes < 60 -> "${elapsedMinutes}M AGO"
            elapsedMinutes < 24 * 60 -> "${elapsedMinutes / 60}H AGO"
            else -> "${elapsedMinutes / (24 * 60)}D AGO"
        }
    }

    private fun formatReset(raw: String?, zoneId: ZoneId): String {
        val instant = raw?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "RESET —"
        val formatter = DateTimeFormatter.ofPattern("MMM d · HH:mm", Locale.US)
        return "RESETS ${formatter.format(instant.atZone(zoneId)).uppercase(Locale.ROOT)}"
    }

    private fun formatPercent(value: Double): String = if (value % 1.0 == 0.0) {
        "${value.toInt()}%"
    } else {
        "${String.format(Locale.US, "%.1f", value)}%"
    }

    private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }

    private fun formatCurrency(value: Double): String = String.format(Locale.US, "%.2f", value)
}

private fun JsonObject.string(name: String): String? = this.get(name)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.int(name: String): Int? = this.get(name)?.takeUnless { it.isJsonNull }?.asInt
private fun JsonObject.long(name: String): Long? = this.get(name)?.takeUnless { it.isJsonNull }?.asLong
private fun JsonObject.double(name: String): Double? = this.get(name)?.takeUnless { it.isJsonNull }?.asDouble
private fun JsonObject.boolean(name: String): Boolean? = this.get(name)?.takeUnless { it.isJsonNull }?.asBoolean
private fun JsonObject.objectOrNull(name: String): JsonObject? =
    this.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
