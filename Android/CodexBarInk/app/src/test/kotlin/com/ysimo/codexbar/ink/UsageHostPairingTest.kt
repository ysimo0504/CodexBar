package com.ysimo.codexbar.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsageHostPairingTest {
    @Test
    fun `parses the Mac pairing payload`() {
        val pairing = UsageHostPairing.parse(
            """
            {
              "version": 1,
              "baseURL": "https://192.168.31.42:43121",
              "token": "reader-token",
              "certificateSHA256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "hostID": "host-12345678"
            }
            """.trimIndent(),
        )

        assertEquals("https://192.168.31.42:43121", pairing.baseURL)
        assertEquals("reader-token", pairing.token)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", pairing.certificateSHA256)
        assertEquals("host-12345678", pairing.hostID)
    }

    @Test
    fun `rejects malformed unsupported and incomplete payloads`() {
        listOf(
            "not-json",
            """{"version":2,"baseURL":"https://192.168.1.2:43121"}""",
            """{"version":1,"baseURL":"https://192.168.1.2:43121"}""",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                UsageHostPairing.parse(value)
            }
        }
    }
}
