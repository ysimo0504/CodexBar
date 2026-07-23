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
import javax.net.ssl.SSLException

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
    private var authenticationBlocked = false
    @Volatile
    private var pairingGeneration = 0L

    fun pairingOrigin(): String? = configStore.pairingOrigin()

    fun savePairing(origin: String, token: String): String? = runCatching {
        configStore.save(origin, token)
        authenticationBlocked = false
        pairingGeneration += 1
        null
    }.getOrElse { error ->
        when (error) {
            is IllegalArgumentException -> error.message ?: "Invalid pairing"
            else -> "Could not save pairing"
        }
    }

    fun clearPairing() {
        pairingGeneration += 1
        configStore.clear()
        preferences.edit().clear().commit()
        authenticationBlocked = false
    }

    fun loadInitial(): Result {
        val stored = preferences.getString(KEY_STATE, null)?.let { raw ->
            runCatching { ReaderReducer.decodeStored(raw) }.getOrNull()
        }
        if (stored != null) {
            return Result(stored, "Cached sanitized last-good")
        }
        if (BuildConfig.TRANSPORT_KIND == "tailnet") {
            return Result(null, "Usage Host not paired", "Tap HOST to pair")
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
        val generation = pairingGeneration
        executor.execute {
            val result = when (BuildConfig.TRANSPORT_KIND) {
                "tailnet" -> fetchUsageHost(previous, generation)
                "fixture" -> fetchFixture(previous)
                else -> loadBundled(previous)
            }
            if (generation == pairingGeneration) {
                mainHandler.post {
                    if (generation == pairingGeneration) completion(result)
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
        return fetch(url, BuildConfig.FIXTURE_TOKEN, previous, "Authenticated fixture host")
    }

    private fun fetchUsageHost(previous: ReaderState?, generation: Long): Result {
        if (authenticationBlocked) {
            return Result(previous, "Authentication paused", "Tap HOST to re-pair")
        }
        val configuration = configStore.load()
            ?: return Result(previous, "Usage Host not paired", "Tap HOST to pair")
        return fetch(
            configuration.endpoint.snapshotUrl,
            configuration.token,
            previous,
            "Authenticated Usage Host",
            generation,
        )
    }

    private fun fetch(
        url: URL,
        token: String,
        previous: ReaderState?,
        successLabel: String,
        pairingGeneration: Long? = null,
    ): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 5_000
                readTimeout = 8_000
                useCaches = false
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
            }
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    if (pairingGeneration != null && pairingGeneration == this.pairingGeneration) {
                        authenticationBlocked = true
                    }
                    throw TransportFailure("Authentication expired · re-pair required")
                }
                in 300..399 -> throw TransportFailure("Redirect blocked · check Usage Host address")
                else -> throw TransportFailure("Usage Host rejected snapshot request")
            }
            val raw = connection.inputStream.use(::readBoundedUtf8)
            val state = ReaderReducer.decodeAndMerge(raw, previous, receivedAtEpochMillis = now())
            if (pairingGeneration == null || pairingGeneration == this.pairingGeneration) {
                persist(state)
            }
            Result(state, successLabel)
        } catch (_: SSLException) {
            Result(previous, "TLS failed · keeping last-good", "TLS verification failed · no fallback")
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
