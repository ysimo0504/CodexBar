package com.ysimo.codexbar.ink

import com.google.gson.Gson
import com.google.gson.JsonParseException

data class UsageHostPairing(
    val baseURL: String,
    val token: String,
    val certificateSHA256: String,
    val hostID: String,
) {
    companion object {
        fun parse(rawValue: String): UsageHostPairing {
            val payload = try {
                Gson().fromJson(rawValue.trim(), Payload::class.java)
            } catch (error: JsonParseException) {
                throw IllegalArgumentException("Pairing JSON is malformed", error)
            }
            require(payload?.version == 1) { "Unsupported pairing JSON version" }
            return UsageHostPairing(
                baseURL = requireField(payload.baseURL, "baseURL"),
                token = requireField(payload.token, "token"),
                certificateSHA256 = requireField(payload.certificateSHA256, "certificateSHA256"),
                hostID = requireField(payload.hostID, "hostID"),
            )
        }

        private fun requireField(value: String?, name: String): String {
            require(!value.isNullOrBlank()) { "Pairing JSON is missing $name" }
            return value
        }
    }

    private data class Payload(
        val version: Int?,
        val baseURL: String?,
        val token: String?,
        val certificateSHA256: String?,
        val hostID: String?,
    )
}
