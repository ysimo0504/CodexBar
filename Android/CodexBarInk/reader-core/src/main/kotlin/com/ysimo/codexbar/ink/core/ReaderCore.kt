package com.ysimo.codexbar.ink.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
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
    val windows: List<WindowState>,
    val credits: CreditState?,
    val todayCostUSD: Double?,
    val plan: String?,
    val statusLabel: String?,
    val errorMessage: String?,
    val errorReason: String?,
    val updatedAt: String?,
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
    val codex: ProviderPresentation?,
    val claude: ProviderPresentation?,
    val genericProviders: List<ProviderPresentation>,
)

data class ProviderPresentation(
    val id: String,
    val name: String,
    val primary: String,
    val secondary: String,
    val status: String,
    val usedPercent: Int?,
)

sealed interface RegionKey {
    data object Header : RegionKey
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
        val display = provider.objectOrNull("display")
        val identity = provider.objectOrNull("identity")
        val status = provider.objectOrNull("status")
        val hasUsableValues = windows.isNotEmpty() || credits != null || cost?.double("todayUSD") != null

        return ProviderState(
            id = id,
            name = provider.string("name")?.takeIf { it.isNotBlank() } ?: previous?.name ?: id,
            sortKey = display?.int("sortKey") ?: previous?.sortKey ?: Int.MAX_VALUE,
            windows = if (hasUsableValues || previous == null) windows else previous.windows,
            credits = credits ?: if (hasUsableValues) null else previous?.credits,
            todayCostUSD = cost?.double("todayUSD") ?: if (hasUsableValues) null else previous?.todayCostUSD,
            plan = identity?.string("plan") ?: previous?.plan,
            statusLabel = status?.string("label") ?: previous?.statusLabel,
            errorMessage = error?.string("message"),
            errorReason = error?.string("reason"),
            updatedAt = provider.string("updatedAt") ?: previous?.updatedAt,
        )
    }
}

object DashboardPresenter {
    fun present(state: ReaderState, nowEpochMillis: Long): DashboardPresentation {
        val generatedAtMillis = runCatching { Instant.parse(state.generatedAt).toEpochMilli() }
            .getOrDefault(state.receivedAtEpochMillis)
        val staleAt = generatedAtMillis + state.staleAfterSeconds * 1_000
        val freshness = if (nowEpochMillis > staleAt) "STALE · showing last good" else "FRESH · snapshot"
        val cards = state.providers.map(::presentProvider)

        return DashboardPresentation(
            freshness = freshness,
            generatedAt = state.generatedAt,
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
        if (previous.codex != current.codex) changes += RegionKey.Provider("codex")
        if (previous.claude != current.claude) changes += RegionKey.Provider("claude")
        if (previous.genericProviders.map { it.id } != current.genericProviders.map { it.id }) {
            changes += RegionKey.ProviderList
        } else {
            current.genericProviders.forEachIndexed { index, provider ->
                if (previous.genericProviders[index] != provider) changes += RegionKey.Provider(provider.id)
            }
        }
        return SemanticChangeSet(changes)
    }

    private fun presentProvider(provider: ProviderState): ProviderPresentation {
        val primaryWindow = provider.windows.firstOrNull()
        val primary = when {
            primaryWindow?.usedPercent != null ->
                "${primaryWindow.label}: ${formatPercent(primaryWindow.usedPercent)} used"
            provider.credits != null ->
                "${formatNumber(provider.credits.remaining)} ${provider.credits.unit} left"
            provider.todayCostUSD != null -> "Today: $${String.format(Locale.US, "%.2f", provider.todayCostUSD)}"
            else -> "No quota data"
        }
        val secondary = provider.windows.drop(1).take(2).joinToString(" · ") { window ->
            val usage = window.usedPercent?.let(::formatPercent) ?: "—"
            "${window.label} $usage"
        }.ifBlank {
            provider.plan?.let { "Plan: $it" } ?: provider.statusLabel.orEmpty()
        }
        val status = when {
            provider.errorMessage != null -> "Last good · ${provider.errorMessage}"
            provider.statusLabel != null -> provider.statusLabel
            else -> "Available"
        }
        return ProviderPresentation(
            id = provider.id,
            name = provider.name,
            primary = primary,
            secondary = secondary,
            status = status,
            usedPercent = primaryWindow?.usedPercent?.roundToInt()?.coerceIn(0, 100),
        )
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
}

private fun JsonObject.string(name: String): String? = this.get(name)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.int(name: String): Int? = this.get(name)?.takeUnless { it.isJsonNull }?.asInt
private fun JsonObject.long(name: String): Long? = this.get(name)?.takeUnless { it.isJsonNull }?.asLong
private fun JsonObject.double(name: String): Double? = this.get(name)?.takeUnless { it.isJsonNull }?.asDouble
private fun JsonObject.boolean(name: String): Boolean? = this.get(name)?.takeUnless { it.isJsonNull }?.asBoolean
private fun JsonObject.objectOrNull(name: String): JsonObject? =
    this.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
