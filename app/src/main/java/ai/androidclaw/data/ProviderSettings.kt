package ai.androidclaw.data

enum class ProviderProtocolFamily {
    Fake,
    OpenAiCompatible,
    Anthropic,
}

enum class ProviderType(
    val storageValue: String,
    val providerId: String,
    val displayName: String,
    val protocolFamily: ProviderProtocolFamily,
    val defaultBaseUrl: String,
    val defaultTimeoutSeconds: Int,
) {
    Fake(
        storageValue = "fake",
        providerId = "fake",
        displayName = "Fake (offline)",
        protocolFamily = ProviderProtocolFamily.Fake,
        defaultBaseUrl = "",
        defaultTimeoutSeconds = 0,
    ),
    OpenAiCompatible(
        storageValue = "openai-compatible",
        providerId = "openai-compatible",
        displayName = "OpenAI-compatible",
        protocolFamily = ProviderProtocolFamily.OpenAiCompatible,
        defaultBaseUrl = OPENAI_DEFAULT_BASE_URL,
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    MiniMax(
        storageValue = "minimax",
        providerId = "minimax",
        displayName = "MiniMax",
        protocolFamily = ProviderProtocolFamily.OpenAiCompatible,
        defaultBaseUrl = MINIMAX_DEFAULT_BASE_URL,
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    Glm(
        storageValue = "glm",
        providerId = "glm",
        displayName = "GLM",
        protocolFamily = ProviderProtocolFamily.OpenAiCompatible,
        defaultBaseUrl = GLM_DEFAULT_BASE_URL,
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    Kimi(
        storageValue = "kimi",
        providerId = "kimi",
        displayName = "Kimi",
        protocolFamily = ProviderProtocolFamily.OpenAiCompatible,
        defaultBaseUrl = KIMI_DEFAULT_BASE_URL,
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    Anthropic(
        storageValue = "anthropic",
        providerId = "anthropic",
        displayName = "Claude",
        protocolFamily = ProviderProtocolFamily.Anthropic,
        defaultBaseUrl = ANTHROPIC_DEFAULT_BASE_URL,
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    Gemini(
        storageValue = "gemini",
        providerId = "gemini",
        displayName = "Gemini",
        protocolFamily = ProviderProtocolFamily.OpenAiCompatible,
        defaultBaseUrl = GEMINI_OPENAI_DEFAULT_BASE_URL,
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    ;

    val requiresRemoteSettings: Boolean
        get() = protocolFamily != ProviderProtocolFamily.Fake

    fun defaultEndpointSettings(): ProviderEndpointSettings {
        return ProviderEndpointSettings(
            baseUrl = defaultBaseUrl,
            modelId = "",
            timeoutSeconds = defaultTimeoutSeconds,
        )
    }

    companion object {
        val configurableProviders: List<ProviderType> = entries.filter { it.requiresRemoteSettings }

        fun fromStorage(value: String?): ProviderType {
            return when (value) {
                "openai" -> OpenAiCompatible
                "claude" -> Anthropic
                else -> entries.firstOrNull { it.storageValue == value } ?: Fake
            }
        }
    }
}

data class ProviderEndpointSettings(
    val baseUrl: String,
    val modelId: String,
    val timeoutSeconds: Int,
)

data class ProviderSettingsSnapshot(
    val providerType: ProviderType = ProviderType.Fake,
    val providerConfigs: Map<ProviderType, ProviderEndpointSettings> = defaultProviderConfigs(),
) {
    fun endpointSettings(providerType: ProviderType): ProviderEndpointSettings {
        return providerConfigs[providerType] ?: providerType.defaultEndpointSettings()
    }

    fun withEndpointSettings(
        providerType: ProviderType,
        settings: ProviderEndpointSettings,
    ): ProviderSettingsSnapshot {
        return copy(providerConfigs = providerConfigs + (providerType to settings))
    }

    val openAiBaseUrl: String
        get() = endpointSettings(ProviderType.OpenAiCompatible).baseUrl

    val openAiModelId: String
        get() = endpointSettings(ProviderType.OpenAiCompatible).modelId

    val openAiTimeoutSeconds: Int
        get() = endpointSettings(ProviderType.OpenAiCompatible).timeoutSeconds
}

private fun defaultProviderConfigs(): Map<ProviderType, ProviderEndpointSettings> {
    return ProviderType.configurableProviders.associateWith { providerType ->
        providerType.defaultEndpointSettings()
    }
}

const val DEFAULT_PROVIDER_TIMEOUT_SECONDS: Int = 60
const val OPENAI_DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
const val MINIMAX_DEFAULT_BASE_URL: String = "https://api.minimax.io/v1"
const val GLM_DEFAULT_BASE_URL: String = "https://open.bigmodel.cn/api/paas/v4"
const val KIMI_DEFAULT_BASE_URL: String = "https://api.moonshot.cn/v1"
const val ANTHROPIC_DEFAULT_BASE_URL: String = "https://api.anthropic.com/v1"
const val GEMINI_OPENAI_DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com/v1beta/openai"
