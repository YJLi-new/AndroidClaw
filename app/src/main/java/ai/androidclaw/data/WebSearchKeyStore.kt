package ai.androidclaw.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val BRAVE_API_KEY_MAX_CHARS = 512

/** Stores the Brave Search API key, encrypted with an Android Keystore key like provider keys. */
interface WebSearchKeyStore {
    suspend fun readBraveApiKey(): String?

    suspend fun writeBraveApiKey(apiKey: String?)
}

/** Process-memory key store for tests and previews. */
class InMemoryWebSearchKeyStore(
    private var apiKey: String? = null,
) : WebSearchKeyStore {
    override suspend fun readBraveApiKey(): String? = apiKey

    override suspend fun writeBraveApiKey(apiKey: String?) {
        this.apiKey = apiKey.toBoundedProviderSecretValue(BRAVE_API_KEY_MAX_CHARS)
    }
}

class AndroidWebSearchKeyStore(
    context: Context,
) : WebSearchKeyStore {
    // Created on first use so the encrypted preferences file is not opened before the first frame.
    private val encryptedStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EncryptedStringStore(
            context = context.applicationContext,
            preferencesName = PREFERENCES_NAME,
            keyAlias = KEY_ALIAS,
        )
    }

    override suspend fun readBraveApiKey(): String? =
        withContext(Dispatchers.IO) {
            encryptedStore.read(BRAVE_STORAGE_KEY).toBoundedProviderSecretValue(BRAVE_API_KEY_MAX_CHARS)
        }

    override suspend fun writeBraveApiKey(apiKey: String?) =
        withContext(Dispatchers.IO) {
            encryptedStore.write(BRAVE_STORAGE_KEY, apiKey.toBoundedProviderSecretValue(BRAVE_API_KEY_MAX_CHARS))
        }

    private companion object {
        const val PREFERENCES_NAME = "androidclaw_search_secrets"
        const val KEY_ALIAS = "androidclaw_search_secret_key"
        const val BRAVE_STORAGE_KEY = "brave_api_key"
    }
}
