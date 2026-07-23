package com.ysimo.codexbar.ink

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class UsageHostConfigStore(context: Context) {
    data class Configuration(
        val endpoint: UsageHostEndpoint,
        val token: String,
        val certificateSHA256: String,
        val hostID: String,
    )

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Configuration? {
        val origin = preferences.getString(KEY_ORIGIN, null) ?: return null
        val initializationVector = preferences.getString(KEY_INITIALIZATION_VECTOR, null) ?: return null
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val certificateSHA256 = preferences.getString(KEY_CERTIFICATE_SHA256, null) ?: return null
        val hostID = preferences.getString(KEY_HOST_ID, null) ?: return null
        return runCatching {
            Configuration(
                endpoint = UsageHostEndpoint.parse(origin),
                token = decrypt(initializationVector, ciphertext),
                certificateSHA256 = normalizeCertificateSHA256(certificateSHA256),
                hostID = normalizeHostID(hostID),
            )
        }.getOrNull()
    }

    fun pairingOrigin(): String? = preferences.getString(KEY_ORIGIN, null)
        ?.let { origin -> runCatching { UsageHostEndpoint.parse(origin).origin }.getOrNull() }

    fun save(origin: String, rawToken: String, rawCertificateSHA256: String, rawHostID: String): Configuration {
        val endpoint = UsageHostEndpoint.parse(origin)
        val token = rawToken.trim()
        require(token.isNotEmpty() && token.none(Char::isWhitespace)) {
            "Enter the reader token without spaces"
        }
        val certificateSHA256 = normalizeCertificateSHA256(rawCertificateSHA256)
        val hostID = normalizeHostID(rawHostID)
        val encrypted = encrypt(token)
        check(
            preferences.edit()
                .putString(KEY_ORIGIN, endpoint.origin)
                .putString(KEY_INITIALIZATION_VECTOR, encrypted.initializationVector)
                .putString(KEY_CIPHERTEXT, encrypted.ciphertext)
                .putString(KEY_CERTIFICATE_SHA256, certificateSHA256)
                .putString(KEY_HOST_ID, hostID)
                .commit(),
        ) {
            "Could not save pairing"
        }
        return Configuration(endpoint, token, certificateSHA256, hostID)
    }

    fun clear() {
        preferences.edit().clear().commit()
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
                load(null)
                deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedValue(
            initializationVector = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
        )
    }

    private fun decrypt(initializationVector: String, ciphertext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(initializationVector, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun normalizeCertificateSHA256(rawValue: String): String {
        val value = rawValue.trim().lowercase()
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Enter the 64-character TLS certificate SHA-256"
        }
        return value
    }

    private fun normalizeHostID(rawValue: String): String {
        val value = rawValue.trim().lowercase()
        require(value.length in 8..128 && value.all { it.isLetterOrDigit() || it == '-' }) {
            "Enter the paired Host ID"
        }
        return value
    }

    private data class EncryptedValue(
        val initializationVector: String,
        val ciphertext: String,
    )

    private companion object {
        const val PREFERENCES_NAME = "usage_host_pairing_v1"
        const val KEY_ORIGIN = "origin"
        const val KEY_INITIALIZATION_VECTOR = "token_iv"
        const val KEY_CIPHERTEXT = "token_ciphertext"
        const val KEY_CERTIFICATE_SHA256 = "certificate_sha256"
        const val KEY_HOST_ID = "host_id"
        const val KEY_ALIAS = "codexbar_ink_reader_token_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
