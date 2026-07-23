package com.ysimo.codexbar.ink

import java.net.URI
import java.net.URL
import java.util.Locale

class UsageHostEndpoint private constructor(
    val origin: String,
    val snapshotUrl: URL,
    val usesPrivateLAN: Boolean,
) {
    companion object {
        fun parse(rawValue: String): UsageHostEndpoint {
            val value = rawValue.trim()
            val uri = runCatching { URI(value) }
                .getOrElse { throw IllegalArgumentException("Enter a valid HTTPS Usage Host address") }
            require(uri.scheme?.lowercase(Locale.US) == "https") {
                "HTTPS is required"
            }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Credentials, queries, and fragments are not allowed"
            }
            val host = uri.host?.lowercase(Locale.US)
                ?: throw IllegalArgumentException("Enter a valid HTTPS Usage Host address")
            val isTailnet = host.endsWith(".ts.net") && host.length > ".ts.net".length
            val isPrivateLAN = isPrivateIPv4(host)
            require(isTailnet || isPrivateLAN) {
                "Use a private-LAN address or optional .ts.net address"
            }
            if (isTailnet) {
                require(uri.port == -1 || uri.port == 443) {
                    "Tailnet HTTPS must use the standard port"
                }
            } else {
                require(uri.port in 1024..65535) {
                    "Private-LAN HTTPS requires its paired port"
                }
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "Enter the host address without a path"
            }

            val authority = if (uri.port == -1) host else "$host:${uri.port}"
            val origin = "https://$authority"
            return UsageHostEndpoint(
                origin = origin,
                snapshotUrl = URL("$origin/dashboard/v1/snapshot"),
                usesPrivateLAN = isPrivateLAN,
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
