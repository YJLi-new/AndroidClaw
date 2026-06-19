package ai.androidclaw.data

import java.net.URI

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
            timeoutSeconds = normalizeProviderTimeoutSeconds(defaultTimeoutSeconds),
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

enum class ProviderEndpointPolicySeverity {
    Error,
    Warning,
}

data class ProviderEndpointPolicyIssue(
    val code: String,
    val severity: ProviderEndpointPolicySeverity,
    val message: String,
)

data class ProviderSettingsSnapshot(
    val providerType: ProviderType = ProviderType.Fake,
    val providerConfigs: Map<ProviderType, ProviderEndpointSettings> = defaultProviderConfigs(),
) {
    fun endpointSettings(providerType: ProviderType): ProviderEndpointSettings = providerConfigs[providerType] ?: providerType.defaultEndpointSettings()

    fun withEndpointSettings(
        providerType: ProviderType,
        settings: ProviderEndpointSettings,
    ): ProviderSettingsSnapshot {
        val fallbackTimeoutSeconds =
            providerType.defaultTimeoutSeconds
                .takeIf { it > 0 }
                ?: DEFAULT_PROVIDER_TIMEOUT_SECONDS
        val normalizedSettings =
            settings.copy(
                timeoutSeconds =
                    normalizeProviderTimeoutSeconds(
                        timeoutSeconds = settings.timeoutSeconds,
                        fallbackSeconds = fallbackTimeoutSeconds,
                    ),
            )
        return copy(
            providerConfigs = providerConfigs + (providerType to normalizedSettings),
        )
    }

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
const val MIN_PROVIDER_TIMEOUT_SECONDS: Int = 1
const val MAX_PROVIDER_TIMEOUT_SECONDS: Int = 600
const val OPENAI_DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
const val OPENAI_CODEX_DEFAULT_BASE_URL: String = "https://chatgpt.com/backend-api/codex"
const val OPENAI_CODEX_LEGACY_DEFAULT_MODEL_ID: String = "gpt-5.3-codex-spark"
const val OPENAI_CODEX_DEFAULT_MODEL_ID: String = "gpt-5.4"
const val MINIMAX_DEFAULT_BASE_URL: String = "https://api.minimax.io/v1"
const val GLM_DEFAULT_BASE_URL: String = "https://open.bigmodel.cn/api/paas/v4"
const val KIMI_DEFAULT_BASE_URL: String = "https://api.moonshot.cn/v1"
const val ANTHROPIC_DEFAULT_BASE_URL: String = "https://api.anthropic.com/v1"
const val GEMINI_OPENAI_DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com/v1beta/openai"
const val DEEPSEEK_DEFAULT_BASE_URL: String = "https://api.deepseek.com"
const val DEEPSEEK_DEFAULT_MODEL_ID: String = "deepseek-v4-flash"

fun normalizeProviderTimeoutSeconds(
    timeoutSeconds: Int,
    fallbackSeconds: Int = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
): Int {
    val safeFallback = fallbackSeconds.coerceIn(MIN_PROVIDER_TIMEOUT_SECONDS, MAX_PROVIDER_TIMEOUT_SECONDS)
    return when {
        timeoutSeconds < MIN_PROVIDER_TIMEOUT_SECONDS -> safeFallback
        timeoutSeconds > MAX_PROVIDER_TIMEOUT_SECONDS -> MAX_PROVIDER_TIMEOUT_SECONDS
        else -> timeoutSeconds
    }
}

fun ProviderEndpointSettings.providerEndpointPolicyIssues(providerType: ProviderType): List<ProviderEndpointPolicyIssue> {
    if (!providerType.requiresRemoteSettings) {
        return emptyList()
    }
    val normalizedBaseUrl = baseUrl.trim()
    if (normalizedBaseUrl.isBlank()) {
        return listOf(
            ProviderEndpointPolicyIssue(
                code = "PROVIDER_BASE_URL_REQUIRED",
                severity = ProviderEndpointPolicySeverity.Error,
                message = "Provider base URL is required.",
            ),
        )
    }
    val parsed =
        runCatching { URI(normalizedBaseUrl) }
            .getOrNull()
            ?: return listOf(
                ProviderEndpointPolicyIssue(
                    code = "PROVIDER_BASE_URL_INVALID",
                    severity = ProviderEndpointPolicySeverity.Error,
                    message = "Provider base URL must be a valid HTTP(S) URL.",
                ),
            )
    val scheme = parsed.scheme?.lowercase()
    val host = parsed.host.orEmpty()
    val issues = mutableListOf<ProviderEndpointPolicyIssue>()
    if (scheme != "http" && scheme != "https") {
        issues +=
            ProviderEndpointPolicyIssue(
                code = "PROVIDER_BASE_URL_UNSUPPORTED_SCHEME",
                severity = ProviderEndpointPolicySeverity.Error,
                message = "Provider base URL must use http or https.",
            )
    }
    if (host.isBlank()) {
        issues +=
            ProviderEndpointPolicyIssue(
                code = "PROVIDER_BASE_URL_MISSING_HOST",
                severity = ProviderEndpointPolicySeverity.Error,
                message = "Provider base URL must include a host.",
            )
    }
    if (!parsed.userInfo.isNullOrBlank()) {
        issues +=
            ProviderEndpointPolicyIssue(
                code = "PROVIDER_BASE_URL_USERINFO",
                severity = ProviderEndpointPolicySeverity.Error,
                message = "Provider base URL must not include credentials.",
            )
    }
    if (!parsed.query.isNullOrBlank() || !parsed.fragment.isNullOrBlank()) {
        issues +=
            ProviderEndpointPolicyIssue(
                code = "PROVIDER_BASE_URL_QUERY_OR_FRAGMENT",
                severity = ProviderEndpointPolicySeverity.Error,
                message = "Provider base URL must not include query parameters or fragments.",
            )
    }
    if (scheme == "http" && !host.isLoopbackProviderHost()) {
        issues +=
            ProviderEndpointPolicyIssue(
                code = "PROVIDER_BASE_URL_INSECURE_HTTP",
                severity = ProviderEndpointPolicySeverity.Warning,
                message = "Provider base URL uses plain HTTP; use HTTPS unless this is a trusted local endpoint.",
            )
    }
    return issues
}

fun ProviderEndpointSettings.firstProviderEndpointPolicyError(providerType: ProviderType): ProviderEndpointPolicyIssue? =
    providerEndpointPolicyIssues(providerType).firstOrNull { issue ->
        issue.severity == ProviderEndpointPolicySeverity.Error
    }

fun String.isLoopbackProviderHost(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "localhost" ||
        normalized == "127.0.0.1" ||
        normalized == "::1" ||
        normalized == "[::1]"
}
