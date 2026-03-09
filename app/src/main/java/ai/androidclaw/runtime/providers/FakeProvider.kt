package ai.androidclaw.runtime.providers

import java.time.Clock
import java.time.format.DateTimeFormatter

class FakeProvider(
    private val clock: Clock,
) : ModelProvider {
    override val id: String = "fake"

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(clock.instant())
        val skills = request.enabledSkills.takeIf { it.isNotEmpty() }?.joinToString { it.name } ?: "none"
        val tools = request.toolDescriptors.joinToString { it.name }
        val lastUserMessage = request.messageHistory.lastOrNull { it.role == ModelMessageRole.User }
            ?.content
            ?.trim()
            .orEmpty()
        return ModelResponse(
            text = buildString {
                appendLine("FakeProvider [$timestamp]")
                appendLine("Session: ${request.sessionId}")
                appendLine("Request: ${request.requestId ?: "none"}")
                appendLine("Run mode: ${request.runMode.name.lowercase()}")
                appendLine("Skills: $skills")
                appendLine("Tools: $tools")
                append("Reply: $lastUserMessage")
            },
        )
    }
}
