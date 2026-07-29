package com.ysimo.codexbar.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusTimerMathTest {
    @Test
    fun `running timer follows its elapsed realtime deadline`() {
        assertEquals(
            24 * 60_000L + 59_250L,
            FocusTimerMath.remainingMillis(
                running = true,
                endsAtElapsedMillis = 1_600_000L,
                pausedRemainingMillis = 25 * 60_000L,
                nowElapsedMillis = 100_750L,
            ),
        )
        assertEquals(
            0L,
            FocusTimerMath.remainingMillis(
                running = true,
                endsAtElapsedMillis = 100L,
                pausedRemainingMillis = 25 * 60_000L,
                nowElapsedMillis = 101L,
            ),
        )
    }

    @Test
    fun `paused timer keeps its captured remaining time`() {
        assertEquals(
            42_000L,
            FocusTimerMath.remainingMillis(
                running = false,
                endsAtElapsedMillis = 0L,
                pausedRemainingMillis = 42_000L,
                nowElapsedMillis = 900_000L,
            ),
        )
    }

    @Test
    fun `persisted deadline catches up after the app is reopened`() {
        assertEquals(
            22_000L,
            FocusTimerMath.remainingMillis(
                running = true,
                endsAtElapsedMillis = 80_000L,
                pausedRemainingMillis = 25 * 60_000L,
                nowElapsedMillis = 58_000L,
            ),
        )
    }

    @Test
    fun `remaining time is shown as a live minute second countdown`() {
        assertEquals("25:00", FocusTimerMath.formatRemaining(25 * 60_000L))
        assertEquals("24:59", FocusTimerMath.formatRemaining(24 * 60_000L + 58_001L))
        assertEquals("00:01", FocusTimerMath.formatRemaining(1L))
        assertEquals("00:00", FocusTimerMath.formatRemaining(0L))
    }

    @Test
    fun `running timer ticks every second`() {
        assertEquals(
            1_000L,
            FocusTimerMath.nextTickDelayMillis(
                running = true,
                nowWallClockMillis = 123_456L,
            ),
        )
    }

    @Test
    fun `idle clock aligns its tick to the next minute`() {
        assertEquals(
            56_544L,
            FocusTimerMath.nextTickDelayMillis(
                running = false,
                nowWallClockMillis = 123_456L,
            ),
        )
        assertEquals(
            60_000L,
            FocusTimerMath.nextTickDelayMillis(
                running = false,
                nowWallClockMillis = 120_000L,
            ),
        )
    }
}
