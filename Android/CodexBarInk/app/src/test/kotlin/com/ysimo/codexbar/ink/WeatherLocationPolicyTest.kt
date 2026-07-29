package com.ysimo.codexbar.ink

import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherLocationPolicyTest {
    @Test
    fun `precise location tries GPS before network`() {
        assertEquals(
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
            WeatherLocationPolicy.providers(
                fineLocationGranted = true,
                gpsEnabled = true,
                networkEnabled = true,
            ),
        )
    }

    @Test
    fun `approximate location uses network only`() {
        assertEquals(
            listOf(LocationManager.NETWORK_PROVIDER),
            WeatherLocationPolicy.providers(
                fineLocationGranted = false,
                gpsEnabled = true,
                networkEnabled = true,
            ),
        )
    }
}
