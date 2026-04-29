package ai.androidclaw.data

enum class ProviderProtocolFamily {
    Fake,
    OpenAiCompatible,
    OpenAiCodex,
    Anthropic,
}

enum class ProviderAuthMode {
    None,
    ApiKey,
    OpenAiCodexDeviceCode,
}

enum class ProviderType(
    val storageValue: String,
    val providerId: String,
    val displayName: String,
    val protocolFamily: ProviderProtocolFamily,
    val authMode: ProviderAuthMode,
    val defaultBaseUrl: String,
    val defaultModelId: String,
    val defaultTimeoutSeconds: Int,
) {
    Fake(
        storageValue = "fake",
        providerId = "fake",
        displayName = "Fake (offline)",
        protocolFamily = ProviderProtocolFamily.Fake,
        authMode = ProviderAuthMode.None,
        defaultBaseUrl = "",
        defaultModelId = "",
        defaultTimeoutSeconds = 0,
    ),
    OpenAiCompatible(
        storageValue = "openai-compatible",
        providerId = "openai-compatible",
        displayName = "OpenAI-compatible",
        protocolFamily = ProviderProtocolFamily.OpenAiCompatible,
        authMode = ProviderAuthMode.ApiKey,
        defaultBaseUrl = OPENAI_DEFAULT_BASE_URL,
        defaultModelId = "",
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    OpenAiCodex(
        storageValue = "openai-codex",
        providerId = "openai-codex",
        displayName = "OpenAI Codex",
        protocolFamily = ProviderProtocolFamily.OpenAiCodex,
        authMode = ProviderAuthMode.OpenAiCodexDeviceCode,
        defaultBaseUrl = OPENAI_CODEX_DEFAULT_BASE_URL,
        defaultModelId = OPENAI_CODEX_DEFAULT_MODEL_ID,
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    MiniMax(
        storageValue = "minimax",
        providerId = "minimax",
        displayName = "MiniMax",
        protocolFamily = ProviderProtocolFamily.OpenAiCompatible,
        authMode = ProviderAuthMode.ApiKey,
        defaultBaseUrl = MINIMAX_DEFAULT_BASE_URL,
        defaultModelId = "",
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    Glm(
        storageValue = "glm",
        providerId = "glm",
        displayName = "GLM",
        protocolFamily = ProviderProtocolFamily.OpenAiCompatible,
        authMode = ProviderAuthMode.ApiKey,
        defaultBaseUrl = GLM_DEFAULT_BASE_URL,
        defaultModelId = "",
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    Kimi(
        storageValue = "kimi",
        providerId = "kimi",
        displayName = "Kimi",
        protocolFamily = ProviderProtocolFamily.OpenAiCompatible,
        authMode = ProviderAuthMode.ApiKey,
        defaultBaseUrl = KIMI_DEFAULT_BASE_URL,
        defaultModelId = "",
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    Anthropic(
        storageValue = "anthropic",
        providerId = "anthropic",
        displayName = "Claude",
        protocolFamily = ProviderProtocolFamily.Anthropic,
        authMode = ProviderAuthMode.ApiKey,
        defaultBaseUrl = ANTHROPIC_DEFAULT_BASE_URL,
        defaultModelId = "",
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    Gemini(
        storageValue = "gemini",
        providerId = "gemini",
        displayName = "Gemini",
        protocolFamily = ProviderProtocolFamily.OpenAiCompatible,
        authMode = ProviderAuthMode.ApiKey,
        defaultBaseUrl = GEMINI_OPENAI_DEFAULT_BASE_URL,
        defaultModelId = "",
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    DeepSeek(
        storageValue = "deepseek",
        providerId = "deepseek",
        displayName = "DeepSeek",
        protocolFamily = ProviderProtocolFamily.OpenAiCompatible,
        authMode = ProviderAuthMode.ApiKey,
        defaultBaseUrl = DEEPSEEK_DEFAULT_BASE_URL,
        defaultModelId = DEEPSEEK_DEFAULT_MODEL_ID,
        defaultTimeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    ),
    ;

    val requiresRemoteSettings: Boolean
        get() = protocolFamily != ProviderProtocolFamily.Fake

    val requiresApiKey: Boolean
        get() = authMode == ProviderAuthMode.ApiKey

    val usesOpenAiCodexOAuth: Boolean
        get() = authMode == ProviderAuthMode.OpenAiCodexDeviceCode

    fun defaultEndpointSettings(): ProviderEndpointSettings =
        ProviderEndpointSettings(
            baseUrl = defaultBaseUrl,
            modelId = defaultModelId,
            timeoutSeconds = defaultTimeoutSeconds,
        )

    companion object {
        val configurableProviders: List<ProviderType> = entries.filter { it.requiresRemoteSettings }

        fun fromStorage(value: String?): ProviderType =
            when (value) {
                "openai" -> OpenAiCompatible
                "claude" -> Anthropic
                else -> entries.firstOrNull { it.storageValue == value } ?: Fake
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
    fun endpointSettings(providerType: ProviderType): ProviderEndpointSettings = providerConfigs[providerType] ?: providerType.defaultEndpointSettings()

    fun withEndpointSettings(
        providerType: ProviderType,
        settings: ProviderEndpointSettings,
    ): ProviderSettingsSnapshot = copy(providerConfigs = providerConfigs + (providerType to settings))

    val openAiBaseUrl: String
        get() = endpointSettings(ProviderType.OpenAiCompatible).baseUrl

    val openAiModelId: String
        get() = endpointSettings(ProviderType.OpenAiCompatible).modelId

    val openAiTimeoutSeconds: Int
        get() = endpointSettings(ProviderType.OpenAiCompatible).timeoutSeconds
}

private fun defaultProviderConfigs(): Map<ProviderType, ProviderEndpointSettings> =
    ProviderType.configurableProviders.associateWith { providerType ->
        providerType.defaultEndpointSettings()
    }

const val DEFAULT_PROVIDER_TIMEOUT_SECONDS: Int = 60
const val OPENAI_DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
const val OPENAI_CODEX_DEFAULT_BASE_URL: String = "https://chatgpt.com/backend-api/codex"
const val OPENAI_CODEX_DEFAULT_MODEL_ID: String = "gpt-5.3-codex-spark"
const val MINIMAX_DEFAULT_BASE_URL: String = "https://api.minimax.io/v1"
const val GLM_DEFAULT_BASE_URL: String = "https://open.bigmodel.cn/api/paas/v4"
const val KIMI_DEFAULT_BASE_URL: String = "https://api.moonshot.cn/v1"
const val ANTHROPIC_DEFAULT_BASE_URL: String = "https://api.anthropic.com/v1"
const val GEMINI_OPENAI_DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com/v1beta/openai"
const val DEEPSEEK_DEFAULT_BASE_URL: String = "https://api.deepseek.com"
const val DEEPSEEK_DEFAULT_MODEL_ID: String = "deepseek-v4-flash"
