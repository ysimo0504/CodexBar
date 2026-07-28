package com.ysimo.codexbar.ink

import android.content.Context

class UsageHostConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): UsageHostEndpoint? {
        val origin = preferences.getString(KEY_ORIGIN, null) ?: return null
        runCatching { UsageHostEndpoint.parse(origin) }.getOrNull()?.let { return it }
        if (origin.startsWith("https://")) {
            return runCatching { save("http://${origin.removePrefix("https://")}") }.getOrNull()
        }
        return null
    }

    fun hostOrigin(): String? = load()?.origin

    fun save(origin: String): UsageHostEndpoint {
        val endpoint = UsageHostEndpoint.parse(origin)
        check(
            preferences.edit()
                .clear()
                .putString(KEY_ORIGIN, endpoint.origin)
                .commit(),
        ) {
            "Could not save Host"
        }
        return endpoint
    }

    private companion object {
        const val PREFERENCES_NAME = "usage_host_pairing_v1"
        const val KEY_ORIGIN = "origin"
    }
}
