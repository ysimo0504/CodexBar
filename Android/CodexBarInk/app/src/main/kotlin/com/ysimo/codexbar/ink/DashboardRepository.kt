package com.ysimo.codexbar.ink

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ysimo.codexbar.ink.core.ReaderReducer
import com.ysimo.codexbar.ink.core.ReaderState
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

class DashboardRepository(private val context: Context) {
    data class Result(
        val state: ReaderState?,
        val sourceLabel: String,
        val errorLabel: String? = null,
    )

    private val preferences = context.getSharedPreferences("reader_last_good", Context.MODE_PRIVATE)
    private val configStore = UsageHostConfigStore(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var hostGeneration = 0L

    fun hostOrigin(): String? = configStore.hostOrigin()

    fun saveHost(origin: String): String? = runCatching {
        configStore.save(origin)
        preferences.edit().remove(KEY_STATE).commit()
        hostGeneration += 1
        null
    }.getOrElse { error ->
        when (error) {
            is IllegalArgumentException -> error.message ?: "Invalid Host"
            else -> "Could not save Host"
        }
    }

    fun loadInitial(): Result {
        val stored = preferences.getString(KEY_STATE, null)?.let { raw ->
            runCatching { ReaderReducer.decodeStored(raw) }.getOrNull()
        }
        if (stored != null) {
            return Result(stored, "Cached sanitized last-good")
        }
        if (BuildConfig.TRANSPORT_KIND == "lan") {
            return Result(null, "Host not set", "Open settings")
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
        val generation = hostGeneration
        executor.execute {
            val result = when (BuildConfig.TRANSPORT_KIND) {
                "lan" -> fetchUsageHost(previous, generation)
                "fixture" -> fetchFixture(previous)
                else -> loadBundled(previous)
            }
            if (generation == hostGeneration) {
                mainHandler.post {
                    if (generation == hostGeneration) completion(result)
                }
            }
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
        val url = runCatching { URL(BuildConfig.FIXTURE_URL) }.getOrNull()
            ?: return Result(previous, "Fixture not configured", "Use an exact snapshot fixture URL")
        if (url.path != SNAPSHOT_PATH) {
            return Result(previous, "Fixture rejected", "Use an exact snapshot fixture URL")
        }
        return fetch(url, previous, "Authenticated fixture host", token = BuildConfig.FIXTURE_TOKEN)
    }

    private fun fetchUsageHost(previous: ReaderState?, generation: Long): Result {
        val endpoint = configStore.load()
            ?: return Result(previous, "Host not set", "Open settings")
        return fetch(
            endpoint.snapshotUrl,
            previous,
            "LAN Usage Host",
            generation,
        )
    }

    private fun fetch(
        url: URL,
        previous: ReaderState?,
        successLabel: String,
        hostGeneration: Long? = null,
        token: String? = null,
    ): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 5_000
                readTimeout = 8_000
                useCaches = false
                if (!token.isNullOrEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
            }
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                in 300..399 -> throw TransportFailure("Redirect blocked · check Usage Host address")
                else -> throw TransportFailure("Usage Host rejected snapshot request")
            }
            val raw = connection.inputStream.use(::readBoundedUtf8)
            val state = ReaderReducer.decodeAndMerge(raw, previous, receivedAtEpochMillis = now())
            if (hostGeneration == null || hostGeneration == this.hostGeneration) {
                persist(state)
            }
            Result(state, successLabel)
        } catch (_: SocketTimeoutException) {
            Result(previous, "Network failed · keeping last-good", "Usage Host timed out")
        } catch (error: TransportFailure) {
            Result(previous, "Network failed · keeping last-good", error.safeMessage)
        } catch (error: IllegalArgumentException) {
            Result(previous, "Snapshot rejected · keeping last-good", error.message ?: "Invalid snapshot")
        } catch (_: Exception) {
            Result(previous, "Network failed · keeping last-good", "Usage Host temporarily unavailable")
        } finally {
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

    private fun readBoundedUtf8(stream: java.io.InputStream): String {
        val bytes = ByteArray(MAX_SNAPSHOT_BYTES + 1)
        var total = 0
        while (total < bytes.size) {
            val read = stream.read(bytes, total, bytes.size - total)
            if (read < 0) break
            total += read
        }
        require(total <= MAX_SNAPSHOT_BYTES) { "Snapshot is too large" }
        return bytes.copyOf(total).toString(Charsets.UTF_8)
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val KEY_STATE = "dashboard_state_v1"
        const val FIXTURE_NAME = "dashboard-snapshot-v1-canonical.json"
        const val SNAPSHOT_PATH = "/dashboard/v1/snapshot"
        const val MAX_SNAPSHOT_BYTES = 1_048_576
    }

    private class TransportFailure(val safeMessage: String) : Exception()
}
