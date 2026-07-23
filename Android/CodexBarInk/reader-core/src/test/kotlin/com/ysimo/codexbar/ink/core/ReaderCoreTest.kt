package com.ysimo.codexbar.ink.core

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
        val presentation = DashboardPresenter.present(state, nowEpochMillis = 1)

        assertEquals("Codex", presentation.codex?.name)
        assertEquals("Claude", presentation.claude?.name)
        assertEquals(listOf("future-provider"), presentation.genericProviders.map { it.id })
        assertTrue(presentation.claude?.status?.contains("Temporarily unavailable") == true)
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
    fun `provider error retains previous usable values`() {
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

        assertEquals(previous.providers.first { it.id == "codex" }.windows, next.providers.single().windows)
        assertEquals("Temporarily unavailable", next.providers.single().errorMessage)
    }

    @Test
    fun `same presentation emits no semantic changes`() {
        val state = ReaderReducer.decodeAndMerge(fixture, previous = null, receivedAtEpochMillis = 1)
        val presentation = DashboardPresenter.present(state, nowEpochMillis = 1)

        assertTrue(DashboardPresenter.diff(presentation, presentation).isEmpty)
        assertIs<RegionKey.Root>(DashboardPresenter.diff(null, presentation).regions.single())
    }
}
