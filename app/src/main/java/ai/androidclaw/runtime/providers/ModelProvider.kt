package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderType
import ai.androidclaw.runtime.tools.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

enum class ModelRunMode {
    Interactive,
    Scheduled,
}

enum class ModelMessageRole {
    System,
    User,
    Assistant,
    Tool,
}

data class ModelMessage(
    val role: ModelMessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<ProviderToolCall> = emptyList(),
)

data class ProviderToolCall(
    val id: String,
    val name: String,
    val argumentsJson: JsonObject,
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
    val finishReason: String = "stop",
    val toolCalls: List<ProviderToolCall> = emptyList(),
)

sealed interface ModelStreamEvent {
    data class TextDelta(val text: String) : ModelStreamEvent

    data class ToolCallDelta(
        val index: Int,
        val idPart: String? = null,
        val namePart: String? = null,
        val argumentsPart: String? = null,
    ) : ModelStreamEvent

    data class Completed(val response: ModelResponse) : ModelStreamEvent
}

data class ProviderCapabilities(
    val supportsStreamingText: Boolean = false,
    val supportsStreamingToolCalls: Boolean = false,
    val supportsImages: Boolean = false,
    val supportsFiles: Boolean = false,
)

interface ModelProvider {
    val id: String
    val capabilities: ProviderCapabilities
        get() = ProviderCapabilities()

    suspend fun generate(request: ModelRequest): ModelResponse

    fun streamGenerate(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        emit(ModelStreamEvent.Completed(generate(request)))
    }
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
    InvalidEndpoint,
    Offline,
    Authentication,
    Network,
    Timeout,
    Server,
    StreamInterrupted,
    Response,
}

class ModelProviderException(
    val kind: ModelProviderFailureKind,
    val userMessage: String,
    val details: String? = null,
    cause: Throwable? = null,
) : Exception(userMessage, cause)
