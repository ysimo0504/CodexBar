package com.ysimo.codexbar.ink

import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedTLSTest {
    @Test
    fun `matches only the exact certificate digest`() {
        val certificate = "fixture-certificate".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate)
            .joinToString("") { byte -> "%02x".format(byte) }
        val pin = CertificatePin.parse(digest)

        assertTrue(pin.matchesEncoded(certificate))
        assertFalse(pin.matchesEncoded("different-certificate".toByteArray()))
    }

    @Test
    fun `rejects malformed certificate pins`() {
        listOf(
            "",
            "abc",
            "g".repeat(64),
            "a".repeat(63),
            "a".repeat(65),
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                CertificatePin.parse(value)
            }
        }
    }
}
