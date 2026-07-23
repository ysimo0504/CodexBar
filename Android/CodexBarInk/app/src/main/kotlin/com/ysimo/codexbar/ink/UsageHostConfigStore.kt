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
    )

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Configuration? {
        val origin = preferences.getString(KEY_ORIGIN, null) ?: return null
        val initializationVector = preferences.getString(KEY_INITIALIZATION_VECTOR, null) ?: return null
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        return runCatching {
            Configuration(
                endpoint = UsageHostEndpoint.parse(origin),
                token = decrypt(initializationVector, ciphertext),
            )
        }.getOrNull()
    }

    fun pairingOrigin(): String? = preferences.getString(KEY_ORIGIN, null)
        ?.let { origin -> runCatching { UsageHostEndpoint.parse(origin).origin }.getOrNull() }

    fun save(origin: String, rawToken: String): Configuration {
        val endpoint = UsageHostEndpoint.parse(origin)
        val token = rawToken.trim()
        require(token.isNotEmpty() && token.none(Char::isWhitespace)) {
            "Enter the reader token without spaces"
        }
        val encrypted = encrypt(token)
        check(
            preferences.edit()
                .putString(KEY_ORIGIN, endpoint.origin)
                .putString(KEY_INITIALIZATION_VECTOR, encrypted.initializationVector)
                .putString(KEY_CIPHERTEXT, encrypted.ciphertext)
                .commit(),
        ) {
            "Could not save pairing"
        }
        return Configuration(endpoint, token)
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

    private data class EncryptedValue(
        val initializationVector: String,
        val ciphertext: String,
    )

    private companion object {
        const val PREFERENCES_NAME = "usage_host_pairing_v1"
        const val KEY_ORIGIN = "origin"
        const val KEY_INITIALIZATION_VECTOR = "token_iv"
        const val KEY_CIPHERTEXT = "token_ciphertext"
        const val KEY_ALIAS = "codexbar_ink_reader_token_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
