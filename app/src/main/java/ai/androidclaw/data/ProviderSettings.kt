package ai.androidclaw.data

enum class ProviderType(
    val storageValue: String,
    val providerId: String,
    val displayName: String,
) {
    Fake(
        storageValue = "fake",
        providerId = "fake",
        displayName = "Fake (offline)",
    ),
    OpenAiCompatible(
        storageValue = "openai-compatible",
        providerId = "openai-compatible",
        displayName = "OpenAI-compatible",
    ),
    ;

    companion object {
        fun fromStorage(value: String?): ProviderType {
            return when (value) {
                "openai" -> OpenAiCompatible
                else -> entries.firstOrNull { it.storageValue == value } ?: Fake
            }
        }
    }
}

data class ProviderSettingsSnapshot(
    val providerType: ProviderType = ProviderType.Fake,
    val openAiBaseUrl: String = OPENAI_DEFAULT_BASE_URL,
    val openAiModelId: String = "",
    val openAiTimeoutSeconds: Int = OPENAI_DEFAULT_TIMEOUT_SECONDS,
)

const val OPENAI_DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
const val OPENAI_DEFAULT_TIMEOUT_SECONDS: Int = 60
