package com.ysimo.codexbar.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayPaletteTest {
    @Test
    fun `color display uses restrained provider accents`() {
        assertEquals(DisplayAccent.BLUE, DisplayPalette.accent("codex", supportsColor = true))
        assertEquals(DisplayAccent.VIOLET, DisplayPalette.accent("claude", supportsColor = true))
        assertEquals(DisplayAccent.INK, DisplayPalette.accent("future-provider", supportsColor = true))
    }

    @Test
    fun `monochrome display always uses high contrast ink`() {
        assertEquals(DisplayAccent.INK, DisplayPalette.accent("codex", supportsColor = false))
        assertEquals(DisplayAccent.INK, DisplayPalette.accent("claude", supportsColor = false))
    }

    @Test
    fun `boox capability combines sdk display and model signals`() {
        assertEquals(true, BooxDisplayCapability.supportsColor(0, false, "Leaf3C"))
        assertEquals(true, BooxDisplayCapability.supportsColor(2, false, "Unknown"))
        assertEquals(true, BooxDisplayCapability.supportsColor(null, true, "Unknown"))
        assertEquals(false, BooxDisplayCapability.supportsColor(0, false, "Leaf3"))
        assertEquals(false, BooxDisplayCapability.supportsColor(null, false, "Palma"))
    }
}
