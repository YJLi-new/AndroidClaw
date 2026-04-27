package ai.androidclaw.data

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProviderOAuthCredential(
    val provider: String,
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_at_epoch_millis")
    val expiresAtEpochMillis: Long,
    val email: String? = null,
    @SerialName("profile_name")
    val profileName: String? = null,
    @SerialName("chatgpt_account_id")
    val chatGptAccountId: String? = null,
)

interface ProviderSecretStore {
    suspend fun readApiKey(providerType: ProviderType): String?

    suspend fun writeApiKey(
        providerType: ProviderType,
        apiKey: String?,
    )

    suspend fun readOAuthCredential(providerType: ProviderType): ProviderOAuthCredential?

    suspend fun writeOAuthCredential(
        providerType: ProviderType,
        credential: ProviderOAuthCredential?,
    )

    suspend fun consumeRecoveryNotice(providerType: ProviderType): Boolean
}

class AndroidProviderSecretStore(
    context: Context,
) : ProviderSecretStore {
    private val encryptedStore =
        EncryptedStringStore(
            context = context,
            preferencesName = PREFERENCES_NAME,
            keyAlias = KEY_ALIAS,
        )
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    override suspend fun readApiKey(providerType: ProviderType): String? = encryptedStore.read(storageKey(providerType))

    override suspend fun writeApiKey(
        providerType: ProviderType,
        apiKey: String?,
    ) {
        encryptedStore.write(storageKey(providerType), apiKey)
    }

    override suspend fun readOAuthCredential(providerType: ProviderType): ProviderOAuthCredential? {
        val payload = encryptedStore.read(oAuthStorageKey(providerType)) ?: return null
        return try {
            json.decodeFromString<ProviderOAuthCredential>(payload)
        } catch (_: SerializationException) {
            encryptedStore.write(oAuthStorageKey(providerType), null)
            null
        } catch (_: IllegalArgumentException) {
            encryptedStore.write(oAuthStorageKey(providerType), null)
            null
        }
    }

    override suspend fun writeOAuthCredential(
        providerType: ProviderType,
        credential: ProviderOAuthCredential?,
    ) {
        encryptedStore.write(
            storageKey = oAuthStorageKey(providerType),
            value = credential?.let { json.encodeToString(it) },
        )
    }

    override suspend fun consumeRecoveryNotice(providerType: ProviderType): Boolean = encryptedStore.consumeRecoveryNotice(storageKey(providerType))

    private fun storageKey(providerType: ProviderType): String = "api_key_${providerType.storageValue}"

    private fun oAuthStorageKey(providerType: ProviderType): String = "oauth_${providerType.storageValue}"

    private companion object {
        const val PREFERENCES_NAME = "androidclaw_provider_secrets"
        const val KEY_ALIAS = "androidclaw_provider_secret_key"
    }
}
