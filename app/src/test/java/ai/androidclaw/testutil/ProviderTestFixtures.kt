package ai.androidclaw.testutil

import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderType
import ai.androidclaw.runtime.providers.FakeProvider
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.providers.ProviderRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val testClock: Clock = Clock.fixed(
    Instant.parse("2026-03-08T00:00:00Z"),
    ZoneOffset.UTC,
)

class InMemoryProviderSecretStore(
    initialSecrets: Map<ProviderType, String> = emptyMap(),
) : ProviderSecretStore {
    private val secrets = initialSecrets.toMutableMap()

    override suspend fun readApiKey(providerType: ProviderType): String? {
        return secrets[providerType]
    }

    override suspend fun writeApiKey(providerType: ProviderType, apiKey: String?) {
        if (apiKey.isNullOrBlank()) {
            secrets.remove(providerType)
        } else {
            secrets[providerType] = apiKey.trim()
        }
    }

    fun clear() {
        secrets.clear()
    }
}

fun buildTestProviderRegistry(
    fakeProvider: ModelProvider = FakeProvider(clock = testClock),
    openAiCompatibleProvider: ModelProvider = StubModelProvider(ProviderType.OpenAiCompatible.providerId),
    anthropicProvider: ModelProvider = StubModelProvider(ProviderType.Anthropic.providerId),
): ProviderRegistry {
    return ProviderRegistry(
        providers = listOf(
            ProviderRegistry.RegisteredProviderEntry(
                type = ProviderType.Fake,
                displayName = ProviderType.Fake.displayName,
                provider = fakeProvider,
            ),
        ) + ProviderType.configurableProviders.map { providerType ->
            ProviderRegistry.RegisteredProviderEntry(
                type = providerType,
                displayName = providerType.displayName,
                provider = when (providerType) {
                    ProviderType.Anthropic -> anthropicProvider
                    ProviderType.OpenAiCompatible -> openAiCompatibleProvider
                    else -> StubModelProvider(providerType.providerId)
                },
            )
        },
    )
}

private class StubModelProvider(
    override val id: String,
) : ModelProvider {
    override suspend fun generate(request: ModelRequest): ModelResponse {
        return ModelResponse(text = "Stub response")
    }
}
