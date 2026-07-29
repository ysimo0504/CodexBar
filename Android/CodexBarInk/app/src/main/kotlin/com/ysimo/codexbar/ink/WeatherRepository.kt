package com.ysimo.codexbar.ink

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

data class WeatherSnapshot(
    val temperatureCelsius: Double,
    val humidityPercent: Int,
    val windSpeedKmh: Double,
    val weatherCode: Int,
    val updatedAtEpochMillis: Long,
)

class WeatherRepository(context: Context) {
    private data class ForecastResponse(val current: CurrentWeather?)
    private data class NetworkLocation(
        val latitude: Double?,
        val longitude: Double?,
    )

    private data class CurrentWeather(
        @SerializedName("temperature_2m") val temperatureCelsius: Double?,
        @SerializedName("relative_humidity_2m") val humidityPercent: Int?,
        @SerializedName("wind_speed_10m") val windSpeedKmh: Double?,
        @SerializedName("weather_code") val weatherCode: Int?,
    )

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    fun cached(): WeatherSnapshot? = preferences.getString(KEY_LAST_GOOD, null)?.let { raw ->
        runCatching { gson.fromJson(raw, WeatherSnapshot::class.java) }.getOrNull()
    }

    fun refresh(
        latitude: Double,
        longitude: Double,
        completion: (WeatherSnapshot?, Throwable?) -> Unit,
    ) {
        val cached = cached()
        if (cached != null && System.currentTimeMillis() - cached.updatedAtEpochMillis < CACHE_MILLIS) {
            completion(cached, null)
            return
        }
        executor.execute {
            val result = runCatching { fetch(latitude, longitude) }
            result.getOrNull()?.let { snapshot ->
                preferences.edit().putString(KEY_LAST_GOOD, gson.toJson(snapshot)).apply()
            }
            mainHandler.post { completion(result.getOrNull() ?: cached, result.exceptionOrNull()) }
        }
    }

    fun refreshFromNetworkLocation(completion: (WeatherSnapshot?, Throwable?) -> Unit) {
        val cached = cached()
        if (cached != null && System.currentTimeMillis() - cached.updatedAtEpochMillis < CACHE_MILLIS) {
            completion(cached, null)
            return
        }
        executor.execute {
            val result = runCatching {
                val location = fetchNetworkLocation()
                fetch(location.first, location.second)
            }
            result.getOrNull()?.let { snapshot ->
                preferences.edit().putString(KEY_LAST_GOOD, gson.toJson(snapshot)).apply()
            }
            mainHandler.post {
                completion(result.getOrNull() ?: cached, result.exceptionOrNull())
            }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun fetch(latitude: Double, longitude: Double): WeatherSnapshot {
        val endpoint = String.format(
            Locale.US,
            "$BASE_URL?latitude=%.4f&longitude=%.4f&current=" +
                "temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code",
            latitude,
            longitude,
        )
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Weather service unavailable"
            }
            val response = connection.inputStream.bufferedReader().use { reader ->
                gson.fromJson(reader, ForecastResponse::class.java)
            }
            val current = requireNotNull(response.current) { "Missing current weather" }
            WeatherSnapshot(
                temperatureCelsius = requireNotNull(current.temperatureCelsius),
                humidityPercent = requireNotNull(current.humidityPercent).coerceIn(0, 100),
                windSpeedKmh = requireNotNull(current.windSpeedKmh).coerceAtLeast(0.0),
                weatherCode = requireNotNull(current.weatherCode),
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchNetworkLocation(): Pair<Double, Double> {
        val connection = URL(NETWORK_LOCATION_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Network location unavailable"
            }
            val response = connection.inputStream.bufferedReader().use { reader ->
                gson.fromJson(reader, NetworkLocation::class.java)
            }
            requireNotNull(response.latitude) to requireNotNull(response.longitude)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PREFERENCES = "weather_last_good"
        const val KEY_LAST_GOOD = "snapshot"
        const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
        const val NETWORK_LOCATION_URL =
            "https://api.bigdatacloud.net/data/reverse-geocode-client?localityLanguage=en"
        const val CACHE_MILLIS = 30 * 60 * 1_000L
    }
}
