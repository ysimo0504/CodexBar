package com.ysimo.codexbar.ink

import java.net.URI
import java.net.URL
import java.util.Locale

class UsageHostEndpoint private constructor(
    val origin: String,
    val snapshotUrl: URL,
) {
    companion object {
        fun parse(rawValue: String): UsageHostEndpoint {
            val value = rawValue.trim()
            val uri = runCatching { URI(value) }
                .getOrElse { throw IllegalArgumentException("Enter a valid HTTPS tailnet address") }
            require(uri.scheme?.lowercase(Locale.US) == "https") {
                "HTTPS is required"
            }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Credentials, queries, and fragments are not allowed"
            }
            require(uri.port == -1 || uri.port == 443) {
                "Only the standard HTTPS port is allowed"
            }
            val host = uri.host?.lowercase(Locale.US)
            require(host != null && host.endsWith(".ts.net") && host.length > ".ts.net".length) {
                "Enter the Usage Host .ts.net address"
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "Enter the host address without a path"
            }

            val origin = "https://$host"
            return UsageHostEndpoint(
                origin = origin,
                snapshotUrl = URL("$origin/dashboard/v1/snapshot"),
            )
        }
    }
}
