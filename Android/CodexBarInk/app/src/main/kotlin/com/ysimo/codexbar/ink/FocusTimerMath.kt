package com.ysimo.codexbar.ink

import java.util.Locale

internal object FocusTimerMath {
    private const val SECOND_MILLIS = 1_000L
    private const val MINUTE_MILLIS = 60 * SECOND_MILLIS

    fun remainingMillis(
        running: Boolean,
        endsAtElapsedMillis: Long,
        pausedRemainingMillis: Long,
        nowElapsedMillis: Long,
    ): Long {
        if (!running) return pausedRemainingMillis.coerceAtLeast(0L)
        return (endsAtElapsedMillis - nowElapsedMillis).coerceAtLeast(0L)
    }

    fun formatRemaining(remainingMillis: Long): String {
        val totalSeconds = (remainingMillis.coerceAtLeast(0L) + 999L) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }

    fun nextTickDelayMillis(running: Boolean, nowWallClockMillis: Long): Long {
        if (running) return SECOND_MILLIS
        return MINUTE_MILLIS - Math.floorMod(nowWallClockMillis, MINUTE_MILLIS)
    }
}
