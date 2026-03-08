package ai.androidclaw.runtime.providers

import java.time.Clock
import java.time.format.DateTimeFormatter

class FakeProvider(
    private val clock: Clock,
) : ModelProvider {
    override val id: String = "fake"

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(clock.instant())
        val skills = request.enabledSkillNames.takeIf { it.isNotEmpty() }?.joinToString() ?: "none"
        val tools = request.toolDescriptors.joinToString { it.name }
        return ModelResponse(
            text = buildString {
                appendLine("FakeProvider [$timestamp]")
                appendLine("Session: ${request.sessionId}")
                appendLine("Skills: $skills")
                appendLine("Tools: $tools")
                append("Reply: ${request.userMessage.trim()}")
            },
        )
    }
}

