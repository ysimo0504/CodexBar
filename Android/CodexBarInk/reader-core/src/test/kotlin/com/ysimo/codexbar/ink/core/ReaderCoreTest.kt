package com.ysimo.codexbar.ink.core

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReaderCoreTest {
    private val fixture = requireNotNull(javaClass.getResource("/dashboard-snapshot-v1-canonical.json")).readText()

    @Test
    fun `canonical fixture produces priority and generic cards`() {
        val state = ReaderReducer.decodeAndMerge(fixture, previous = null, receivedAtEpochMillis = 1)
        val presentation = DashboardPresenter.present(state, nowEpochMillis = 1, zoneId = ZoneId.of("UTC"))

        assertEquals("FRESH · snapshot", presentation.freshness)
        assertEquals("Codex", presentation.codex?.name)
        assertEquals("72% LEFT", presentation.codex?.remaining)
        assertEquals("SESSION · 28% USED", presentation.codex?.primary)
        assertEquals("RESETS JUL 23 · 04:00", presentation.codex?.reset)
        assertTrue(presentation.codex?.secondary?.contains("$18.22 / 30d") == true)
        assertEquals(null, presentation.claude)
        assertEquals(listOf("future-provider"), presentation.genericProviders.map { it.id })
        assertEquals(listOf("codex", "future-provider"), presentation.providers.map { it.id })
        assertEquals(
            listOf("Session", "Weekly", "Future allowance"),
            presentation.codex?.quotas?.map { it.label },
        )
        assertEquals(
            listOf(72, 41, 90),
            presentation.codex?.quotas?.map { it.remainingPercent },
        )
        assertEquals(72, presentation.codex?.remainingPercent)
        assertEquals(
            listOf("TODAY", "LAST 30 DAYS", "CREDITS", "PLAN", "STATUS", "SOURCE"),
            presentation.codex?.metrics?.map { it.label },
        )
        assertEquals("OAUTH", presentation.codex?.metrics?.last()?.value)
        assertEquals(UsageHistoryKind.COST, presentation.codex?.usageHistory?.kind)
        assertEquals(
            listOf(0.55, 1.04),
            presentation.codex?.usageHistory?.points?.map { it.value },
        )
    }

    @Test
    fun `unknown schema rejects the entire snapshot`() {
        assertFailsWith<IllegalArgumentException> {
            ReaderReducer.decodeAndMerge(
                fixture.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                previous = null,
                receivedAtEpochMillis = 1,
            )
        }
    }

    @Test
    fun `used quota alone is converted to remaining progress`() {
        val usedOnlySnapshot = """
            {
              "schemaVersion": 1,
              "generatedAt": "2026-07-23T00:05:00Z",
              "staleAfterSeconds": 900,
              "providers": [{
                "id": "codex",
                "name": "Codex",
                "enabled": true,
                "windows": [{
                  "kind": "session",
                  "label": "Session",
                  "usedPercent": 28
                }]
              }]
            }
        """.trimIndent()
        val state = ReaderReducer.decodeAndMerge(
            usedOnlySnapshot,
            previous = null,
            receivedAtEpochMillis = 1,
        )
        val presentation = DashboardPresenter.present(
            state,
            nowEpochMillis = 1,
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals(72, presentation.codex?.quotas?.single()?.remainingPercent)
        assertEquals(72, presentation.codex?.remainingPercent)
    }

    @Test
    fun `provider error clears previous usage and is omitted from presentation`() {
        val previous = ReaderReducer.decodeAndMerge(fixture, previous = null, receivedAtEpochMillis = 1)
        val failed = """
            {
              "schemaVersion": 1,
              "generatedAt": "2026-07-23T00:05:00Z",
              "staleAfterSeconds": 900,
              "providers": [{
                "id": "codex",
                "name": "Codex",
                "enabled": true,
                "windows": [],
                "credits": null,
                "cost": null,
                "error": {"message": "Temporarily unavailable", "reason": "provider-unavailable"}
              }]
            }
        """.trimIndent()

        val next = ReaderReducer.decodeAndMerge(failed, previous, receivedAtEpochMillis = 2)
        val provider = next.providers.single()
        val presentation = DashboardPresenter.present(next, nowEpochMillis = 2, zoneId = ZoneId.of("UTC"))

        assertTrue(provider.windows.isEmpty())
        assertEquals(null, provider.credits)
        assertEquals(null, provider.todayCostUSD)
        assertEquals(null, provider.last30DaysCostUSD)
        assertEquals(null, provider.plan)
        assertEquals("Temporarily unavailable", provider.errorMessage)
        assertEquals(null, presentation.codex)
    }

    @Test
    fun `same presentation emits no semantic changes`() {
        val state = ReaderReducer.decodeAndMerge(fixture, previous = null, receivedAtEpochMillis = 1)
        val presentation = DashboardPresenter.present(state, nowEpochMillis = 1, zoneId = ZoneId.of("UTC"))

        assertTrue(DashboardPresenter.diff(presentation, presentation).isEmpty)
        assertIs<RegionKey.Root>(DashboardPresenter.diff(null, presentation).regions.single())
    }

    @Test
    fun `stored state from before daily history remains readable`() {
        val current = ReaderReducer.decodeAndMerge(fixture, previous = null, receivedAtEpochMillis = 1)
        val legacyStored = ReaderReducer.encode(current)
            .replace(Regex(",\"dailyUsage\":\\[[^]]*]"), "")
        val restored = ReaderReducer.decodeStored(legacyStored)
        val presentation = DashboardPresenter.present(restored, nowEpochMillis = 1, zoneId = ZoneId.of("UTC"))

        assertEquals(null, presentation.codex?.usageHistory)
        assertEquals("Codex", presentation.codex?.name)
    }
}
