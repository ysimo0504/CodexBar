package com.ysimo.codexbar.ink

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ysimo.codexbar.ink.core.ReaderReducer
import com.ysimo.codexbar.ink.core.ReaderState
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class DashboardRepository(private val context: Context) {
    data class Result(
        val state: ReaderState?,
        val sourceLabel: String,
        val errorLabel: String? = null,
    )

    private val preferences = context.getSharedPreferences("reader_last_good", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun loadInitial(): Result {
        val stored = preferences.getString(KEY_STATE, null)?.let { raw ->
            runCatching { ReaderReducer.decodeStored(raw) }.getOrNull()
        }
        if (stored != null) {
            return Result(stored, "Cached sanitized last-good")
        }
        return runCatching {
            val raw = context.assets.open(FIXTURE_NAME).bufferedReader().use { it.readText() }
            val state = ReaderReducer.decodeAndMerge(raw, previous = null, receivedAtEpochMillis = now())
            persist(state)
            Result(state, "Bundled redacted fixture")
        }.getOrElse { error ->
            Result(null, "No last-good", safeError(error))
        }
    }

    fun refresh(previous: ReaderState?, completion: (Result) -> Unit) {
        executor.execute {
            val result = if (BuildConfig.FIXTURE_URL.isBlank()) {
                loadBundled(previous)
            } else {
                fetchFixture(previous)
            }
            mainHandler.post { completion(result) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun loadBundled(previous: ReaderState?): Result = runCatching {
        val raw = context.assets.open(FIXTURE_NAME).bufferedReader().use { it.readText() }
        val state = ReaderReducer.decodeAndMerge(raw, previous, receivedAtEpochMillis = now())
        persist(state)
        Result(state, "Bundled redacted fixture")
    }.getOrElse { error ->
        Result(previous, "Bundled fixture failed", safeError(error))
    }

    private fun fetchFixture(previous: ReaderState?): Result {
        var connection: HttpURLConnection? = null
        return runCatching {
            val url = URL(BuildConfig.FIXTURE_URL)
            require(url.path == "/dashboard/v1/snapshot") { "Fixture URL must target the snapshot route" }
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 5_000
                readTimeout = 8_000
                setRequestProperty("Authorization", "Bearer ${BuildConfig.FIXTURE_TOKEN}")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
            }
            val status = connection.responseCode
            require(status == HttpURLConnection.HTTP_OK) {
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED) "Fixture authentication failed" else "Fixture HTTP $status"
            }
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val state = ReaderReducer.decodeAndMerge(raw, previous, receivedAtEpochMillis = now())
            persist(state)
            Result(state, "Authenticated fixture host")
        }.getOrElse { error ->
            Result(previous, "Network failed · keeping last-good", safeError(error))
        }.also {
            connection?.disconnect()
        }
    }

    private fun persist(state: ReaderState) {
        preferences.edit().putString(KEY_STATE, ReaderReducer.encode(state)).apply()
    }

    private fun safeError(error: Throwable): String = when (error) {
        is IllegalArgumentException -> error.message ?: "Invalid fixture"
        else -> "Fixture temporarily unavailable"
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val KEY_STATE = "dashboard_state_v1"
        const val FIXTURE_NAME = "dashboard-snapshot-v1-canonical.json"
    }
}
