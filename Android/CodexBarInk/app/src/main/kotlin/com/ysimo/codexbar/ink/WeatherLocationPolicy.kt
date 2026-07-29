package com.ysimo.codexbar.ink

import android.location.LocationManager

object WeatherLocationPolicy {
    fun providers(
        fineLocationGranted: Boolean,
        gpsEnabled: Boolean,
        networkEnabled: Boolean,
    ): List<String> = buildList {
        if (fineLocationGranted && gpsEnabled) add(LocationManager.GPS_PROVIDER)
        if (networkEnabled) add(LocationManager.NETWORK_PROVIDER)
    }
}
