package ai.androidclaw.testutil

import ai.androidclaw.data.ProviderOAuthCredential
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderType
import ai.androidclaw.runtime.providers.FakeProvider
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.providers.OpenAiCodexDeviceCodePrompt
import ai.androidclaw.runtime.providers.OpenAiCodexOAuthClient
import ai.androidclaw.runtime.providers.ProviderRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val testClock: Clock =
    Clock.fixed(
        Instant.parse("2026-03-08T00:00:00Z"),
        ZoneOffset.UTC,
    )

class InMemoryProviderSecretStore(
    initialSecrets: Map<ProviderType, String> = emptyMap(),
    initialOAuthCredentials: Map<ProviderType, ProviderOAuthCredential> = emptyMap(),
    initialRecoveryNotices: Set<ProviderType> = emptySet(),
) : ProviderSecretStore {
    private val secrets = initialSecrets.toMutableMap()
    private val oAuthCredentials = initialOAuthCredentials.toMutableMap()
    private val recoveryNotices = initialRecoveryNotices.toMutableSet()

    override suspend fun readApiKey(providerType: ProviderType): String? = secrets[providerType]

    override suspend fun writeApiKey(
        providerType: ProviderType,
        apiKey: String?,
    ) {
        if (apiKey.isNullOrBlank()) {
            secrets.remove(providerType)
        } else {
            secrets[providerType] = apiKey.trim()
        }
    }

    override suspend fun readOAuthCredential(providerType: ProviderType): ProviderOAuthCredential? = oAuthCredentials[providerType]

    override suspend fun writeOAuthCredential(
        providerType: ProviderType,
        credential: ProviderOAuthCredential?,
    ) {
        if (credential == null) {
            oAuthCredentials.remove(providerType)
        } else {
            oAuthCredentials[providerType] = credential
        }
    }

    override suspend fun consumeRecoveryNotice(providerType: ProviderType): Boolean = recoveryNotices.remove(providerType)

    fun markRecoveryNotice(providerType: ProviderType) {
        recoveryNotices += providerType
    }

    fun clear() {
        secrets.clear()
        oAuthCredentials.clear()
        recoveryNotices.clear()
    }
}

class FakeOpenAiCodexOAuthClient(
    private val loginCredential: ProviderOAuthCredential =
        ProviderOAuthCredential(
            provider = ProviderType.OpenAiCodex.providerId,
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresAtEpochMillis = 1_800_000_000_000,
            email = "codex@example.test",
            profileName = "codex@example.test",
        ),
    private val refreshedCredential: ProviderOAuthCredential = loginCredential,
) : OpenAiCodexOAuthClient {
    var loginCount: Int = 0
        private set
    var refreshCount: Int = 0
        private set

    override suspend fun loginWithDeviceCode(
        onVerification: suspend (OpenAiCodexDeviceCodePrompt) -> Unit,
        onProgress: suspend (String) -> Unit,
    ): ProviderOAuthCredential {
        loginCount += 1
        onProgress("Waiting for device authorization...")
        onVerification(
            OpenAiCodexDeviceCodePrompt(
                verificationUrl = "https://auth.openai.com/codex/device",
                userCode = "ABCD-EFGH",
                expiresInMillis = 900_000,
            ),
        )
        return loginCredential
    }

    override suspend fun refreshCredential(credential: ProviderOAuthCredential): ProviderOAuthCredential {
        refreshCount += 1
        return refreshedCredential
    }
}

fun buildTestProviderRegistry(
    fakeProvider: ModelProvider = FakeProvider(clock = testClock),
    openAiCompatibleProvider: ModelProvider = StubModelProvider(ProviderType.OpenAiCompatible.providerId),
    openAiCodexProvider: ModelProvider = StubModelProvider(ProviderType.OpenAiCodex.providerId),
    anthropicProvider: ModelProvider = StubModelProvider(ProviderType.Anthropic.providerId),
): ProviderRegistry =
    ProviderRegistry(
        providers =
            listOf(
                ProviderRegistry.RegisteredProviderEntry(
                    type = ProviderType.Fake,
                    displayName = ProviderType.Fake.displayName,
                    provider = fakeProvider,
                ),
            ) +
                ProviderType.configurableProviders.map { providerType ->
                    ProviderRegistry.RegisteredProviderEntry(
                        type = providerType,
                        displayName = providerType.displayName,
                        provider =
                            when (providerType) {
                                ProviderType.Anthropic -> anthropicProvider
                                ProviderType.OpenAiCodex -> openAiCodexProvider
                                ProviderType.OpenAiCompatible -> openAiCompatibleProvider
                                else -> StubModelProvider(providerType.providerId)
                            },
                    )
                },
    )

private class StubModelProvider(
    override val id: String,
) : ModelProvider {
    override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse(text = "Stub response")
}
