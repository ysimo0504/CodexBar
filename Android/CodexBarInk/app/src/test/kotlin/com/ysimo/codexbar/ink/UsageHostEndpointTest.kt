package com.ysimo.codexbar.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsageHostEndpointTest {
    @Test
    fun `accepts a private LAN HTTP Host`() {
        val endpoint = UsageHostEndpoint.parse(" http://192.168.31.42:43121/ ")

        assertEquals("http://192.168.31.42:43121", endpoint.origin)
        assertEquals(
            "http://192.168.31.42:43121/dashboard/v1/snapshot",
            endpoint.snapshotUrl.toString(),
        )
    }

    @Test
    fun `rejects HTTPS public credentials query fragments and paths`() {
        listOf(
            "https://192.168.31.42:43121",
            "http://token@192.168.31.42:43121",
            "http://192.168.31.42:43121?token=secret",
            "http://192.168.31.42:43121/#fragment",
            "http://192.168.31.42:43121/usage",
            "http://example.com:43121",
            "http://192.0.2.1:43121",
            "http://192.168.31.42",
            "not a url",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                UsageHostEndpoint.parse(value)
            }
        }
    }
}
