package ai.androidclaw.data

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

internal class EncryptedStringStore(
    context: Context,
    preferencesName: String,
    keyAlias: String,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val keyAlias = keyAlias

    fun read(storageKey: String): String? {
        val payload = preferences.getString(storageKey, null) ?: return null
        return runCatching { decrypt(payload) }.getOrElse {
            preferences.edit()
                .remove(storageKey)
                .putBoolean(recoveryKey(storageKey), true)
                .apply()
            null
        }
    }

    fun write(storageKey: String, value: String?) {
        if (value.isNullOrBlank()) {
            preferences.edit()
                .remove(storageKey)
                .remove(recoveryKey(storageKey))
                .apply()
            return
        }
        preferences.edit()
            .putString(storageKey, encrypt(value.trim()))
            .remove(recoveryKey(storageKey))
            .apply()
    }

    fun consumeRecoveryNotice(storageKey: String): Boolean {
        val recoveryKey = recoveryKey(storageKey)
        val recovered = preferences.getBoolean(recoveryKey, false)
        if (recovered) {
            preferences.edit().remove(recoveryKey).apply()
        }
        return recovered
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$iv:$body"
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split(":", limit = 2)
        require(parts.size == 2) { "Invalid encrypted payload." }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        return cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
        val existingKey = keyStore.getKey(keyAlias, null)
        if (existingKey is SecretKey) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_NAME,
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val KEYSTORE_NAME = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_LENGTH_BITS = 128
        const val RECOVERY_PREFIX = "recovered_secret_"
    }

    private fun recoveryKey(storageKey: String): String {
        return "$RECOVERY_PREFIX$storageKey"
    }
}
