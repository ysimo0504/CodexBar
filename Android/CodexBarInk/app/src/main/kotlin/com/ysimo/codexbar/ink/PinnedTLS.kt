package com.ysimo.codexbar.ink

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal class CertificatePin private constructor(private val expected: ByteArray) {
    fun matches(certificate: X509Certificate): Boolean {
        certificate.checkValidity()
        return matchesEncoded(certificate.encoded)
    }

    internal fun matchesEncoded(encodedCertificate: ByteArray): Boolean {
        val actual = MessageDigest.getInstance("SHA-256").digest(encodedCertificate)
        return MessageDigest.isEqual(actual, expected)
    }

    companion object {
        fun parse(rawValue: String): CertificatePin {
            val value = rawValue.trim().lowercase()
            require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid TLS certificate pin"
            }
            return CertificatePin(
                ByteArray(32) { index ->
                    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                },
            )
        }
    }
}

internal object PinnedTLS {
    fun configure(connection: HttpsURLConnection, certificateSHA256: String) {
        val pin = CertificatePin.parse(certificateSHA256)
        val trustManager = PinnedTrustManager(pin)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), SecureRandom())
        connection.sslSocketFactory = context.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, session ->
            runCatching {
                val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: return@runCatching false
                pin.matches(certificate)
            }.getOrDefault(false)
        }
    }

    private class PinnedTrustManager(private val pin: CertificatePin) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not accepted")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull()
                ?: throw CertificateException("Server certificate missing")
            if (!runCatching { pin.matches(certificate) }.getOrDefault(false)) {
                throw CertificateException("Server certificate pin mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
