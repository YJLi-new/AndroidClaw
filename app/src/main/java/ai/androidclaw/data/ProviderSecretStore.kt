package ai.androidclaw.data

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val PROVIDER_API_KEY_MAX_CHARS = 20_000
internal const val PROVIDER_OAUTH_PROVIDER_MAX_CHARS = 160
internal const val PROVIDER_OAUTH_TOKEN_MAX_CHARS = 40_000
internal const val PROVIDER_OAUTH_PROFILE_FIELD_MAX_CHARS = 512

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

    override suspend fun readApiKey(providerType: ProviderType): String? =
        encryptedStore
            .read(storageKey(providerType))
            .toBoundedProviderSecretValue(PROVIDER_API_KEY_MAX_CHARS)

    override suspend fun writeApiKey(
        providerType: ProviderType,
        apiKey: String?,
    ) {
        encryptedStore.write(
            storageKey = storageKey(providerType),
            value = apiKey.toBoundedProviderSecretValue(PROVIDER_API_KEY_MAX_CHARS),
        )
    }

    override suspend fun readOAuthCredential(providerType: ProviderType): ProviderOAuthCredential? {
        val payload = encryptedStore.read(oAuthStorageKey(providerType)) ?: return null
        return try {
            val decoded = json.decodeFromString<ProviderOAuthCredential>(payload)
            decoded.toNormalizedProviderOAuthCredential(providerType)
                ?: run {
                    encryptedStore.write(oAuthStorageKey(providerType), null)
                    null
                }
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
        val normalizedCredential = credential?.toNormalizedProviderOAuthCredential(providerType)
        encryptedStore.write(
            storageKey = oAuthStorageKey(providerType),
            value = normalizedCredential?.let { json.encodeToString(it) },
        )
    }

    override suspend fun consumeRecoveryNotice(providerType: ProviderType): Boolean =
        listOf(
            storageKey(providerType),
            oAuthStorageKey(providerType),
        ).fold(false) { recovered, key ->
            encryptedStore.consumeRecoveryNotice(key) || recovered
        }

    private fun storageKey(providerType: ProviderType): String = "api_key_${providerType.storageValue}"

    private fun oAuthStorageKey(providerType: ProviderType): String = "oauth_${providerType.storageValue}"

    private companion object {
        const val PREFERENCES_NAME = "androidclaw_provider_secrets"
        const val KEY_ALIAS = "androidclaw_provider_secret_key"
    }
}

internal fun ProviderOAuthCredential.toNormalizedProviderOAuthCredential(providerType: ProviderType): ProviderOAuthCredential? {
    val accessToken = accessToken.toBoundedProviderSecretValue(PROVIDER_OAUTH_TOKEN_MAX_CHARS) ?: return null
    val refreshToken = refreshToken.toBoundedProviderSecretValue(PROVIDER_OAUTH_TOKEN_MAX_CHARS) ?: return null
    return ProviderOAuthCredential(
        provider =
            provider.toBoundedProviderSecretValue(PROVIDER_OAUTH_PROVIDER_MAX_CHARS)
                ?: providerType.providerId,
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochMillis = expiresAtEpochMillis,
        email = email.toBoundedProviderSecretValue(PROVIDER_OAUTH_PROFILE_FIELD_MAX_CHARS),
        profileName = profileName.toBoundedProviderSecretValue(PROVIDER_OAUTH_PROFILE_FIELD_MAX_CHARS),
        chatGptAccountId = chatGptAccountId.toBoundedProviderSecretValue(PROVIDER_OAUTH_PROFILE_FIELD_MAX_CHARS),
    )
}

internal fun String?.toBoundedProviderSecretValue(maxChars: Int): String? =
    this
        ?.trim()
        ?.take(maxChars)
        ?.takeIf(String::isNotBlank)
