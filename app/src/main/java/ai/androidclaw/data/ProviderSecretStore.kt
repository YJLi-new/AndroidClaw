package ai.androidclaw.data

import android.content.Context

interface ProviderSecretStore {
    suspend fun readApiKey(providerType: ProviderType): String?

    suspend fun writeApiKey(providerType: ProviderType, apiKey: String?)

    suspend fun consumeRecoveryNotice(providerType: ProviderType): Boolean
}

class AndroidProviderSecretStore(
    context: Context,
) : ProviderSecretStore {
    private val encryptedStore = EncryptedStringStore(
        context = context,
        preferencesName = PREFERENCES_NAME,
        keyAlias = KEY_ALIAS,
    )

    override suspend fun readApiKey(providerType: ProviderType): String? {
        return encryptedStore.read(storageKey(providerType))
    }

    override suspend fun writeApiKey(providerType: ProviderType, apiKey: String?) {
        encryptedStore.write(storageKey(providerType), apiKey)
    }

    override suspend fun consumeRecoveryNotice(providerType: ProviderType): Boolean {
        return encryptedStore.consumeRecoveryNotice(storageKey(providerType))
    }

    private fun storageKey(providerType: ProviderType): String {
        return "api_key_${providerType.storageValue}"
    }

    private companion object {
        const val PREFERENCES_NAME = "androidclaw_provider_secrets"
        const val KEY_ALIAS = "androidclaw_provider_secret_key"
    }
}
