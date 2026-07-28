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
                .getOrElse { throw IllegalArgumentException("Enter a valid Host address") }
            require(uri.scheme?.lowercase(Locale.US) == "http") {
                "Use an HTTP Host address"
            }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Credentials, queries, and fragments are not allowed"
            }
            val host = uri.host?.lowercase(Locale.US)
                ?: throw IllegalArgumentException("Enter a valid Host address")
            require(isPrivateIPv4(host)) {
                "Use a private-LAN address"
            }
            require(uri.port in 1024..65535) {
                "Enter the Host port"
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "Enter the host address without a path"
            }

            val authority = "$host:${uri.port}"
            val origin = "http://$authority"
            return UsageHostEndpoint(
                origin = origin,
                snapshotUrl = URL("$origin/dashboard/v1/snapshot"),
            )
        }

        private fun isPrivateIPv4(host: String): Boolean {
            val octets = host.split(".").mapNotNull(String::toIntOrNull)
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            return when {
                octets[0] == 10 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                octets[0] == 169 && octets[1] == 254 -> true
                else -> false
            }
        }
    }
}
