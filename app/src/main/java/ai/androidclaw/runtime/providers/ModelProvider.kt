package ai.androidclaw.runtime.providers

import ai.androidclaw.runtime.tools.ToolDescriptor

data class ModelRequest(
    val sessionId: String,
    val userMessage: String,
    val enabledSkillNames: List<String>,
    val toolDescriptors: List<ToolDescriptor>,
    val interactive: Boolean,
)

data class ModelResponse(
    val text: String,
)

interface ModelProvider {
    val id: String

    suspend fun generate(request: ModelRequest): ModelResponse
}

class ProviderRegistry(
    val defaultProvider: ModelProvider,
)

