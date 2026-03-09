package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderType
import ai.androidclaw.runtime.tools.ToolDescriptor

enum class ModelRunMode {
    Interactive,
    Scheduled,
}

enum class ModelMessageRole {
    System,
    User,
    Assistant,
}

data class ModelMessage(
    val role: ModelMessageRole,
    val content: String,
)

data class ModelSkillMetadata(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
)

data class ModelRequest(
    val sessionId: String,
    val requestId: String?,
    val messageHistory: List<ModelMessage>,
    val systemPrompt: String,
    val enabledSkills: List<ModelSkillMetadata>,
    val toolDescriptors: List<ToolDescriptor>,
    val runMode: ModelRunMode,
)

data class ModelResponse(
    val text: String,
    val providerRequestId: String? = null,
)

interface ModelProvider {
    val id: String

    suspend fun generate(request: ModelRequest): ModelResponse
}

data class RegisteredProvider(
    val type: ProviderType,
    val id: String,
    val displayName: String,
)

class ProviderRegistry(
    providers: List<RegisteredProviderEntry>,
) {
    data class RegisteredProviderEntry(
        val type: ProviderType,
        val displayName: String,
        val provider: ModelProvider,
    )

    private val entries = providers.associateBy { it.type }

    val defaultProvider: ModelProvider
        get() = require(ProviderType.Fake)

    fun descriptors(): List<RegisteredProvider> {
        return entries.values.map { entry ->
            RegisteredProvider(
                type = entry.type,
                id = entry.provider.id,
                displayName = entry.displayName,
            )
        }.sortedBy { it.displayName }
    }

    fun require(type: ProviderType): ModelProvider {
        return entries[type]?.provider ?: error("No provider registered for ${type.storageValue}")
    }
}

enum class ModelProviderFailureKind {
    Configuration,
    Authentication,
    Network,
    Timeout,
    Response,
}

class ModelProviderException(
    val kind: ModelProviderFailureKind,
    val userMessage: String,
    val details: String? = null,
    cause: Throwable? = null,
) : Exception(userMessage, cause)
