package com.ysimo.codexbar.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsageHostEndpointTest {
    @Test
    fun `accepts an exact https tailnet origin`() {
        val endpoint = UsageHostEndpoint.parse(" https://codexbar-host.tail1234.ts.net/ ")

        assertEquals("https://codexbar-host.tail1234.ts.net", endpoint.origin)
        assertEquals(
            "https://codexbar-host.tail1234.ts.net/dashboard/v1/snapshot",
            endpoint.snapshotUrl.toString(),
        )
    }

    @Test
    fun `rejects cleartext credentials query fragments and paths`() {
        listOf(
            "http://codexbar-host.tail1234.ts.net",
            "https://token@codexbar-host.tail1234.ts.net",
            "https://codexbar-host.tail1234.ts.net?token=secret",
            "https://codexbar-host.tail1234.ts.net/#secret",
            "https://codexbar-host.tail1234.ts.net/usage",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                UsageHostEndpoint.parse(value)
            }
        }
    }

    @Test
    fun `rejects non tailnet and malformed hosts`() {
        listOf(
            "https://example.com",
            "https://192.0.2.1",
            "not a url",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                UsageHostEndpoint.parse(value)
            }
        }
    }
}
