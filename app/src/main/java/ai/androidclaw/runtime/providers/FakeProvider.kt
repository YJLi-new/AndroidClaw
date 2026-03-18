package ai.androidclaw.runtime.providers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        val lastUserMessage =
            request.messageHistory
                .lastOrNull { it.role == ModelMessageRole.User }
                ?.content
                ?.trim()
                .orEmpty()
        val requestedTool =
            TOOL_MARKER
                .find(lastUserMessage)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val latestToolResult =
            requestedTool?.let { toolName ->
                request.messageHistory.lastOrNull { message ->
                    message.role == ModelMessageRole.Tool &&
                        message.toolName == toolName &&
                        message.toolCallId != null &&
                        message.toolCalls.isEmpty()
                }
            }
        if (requestedTool != null && latestToolResult == null) {
            return ModelResponse(
                text = "FakeProvider requested tool $requestedTool.",
                finishReason = "tool_use",
                toolCalls =
                    listOf(
                        ProviderToolCall(
                            id = "fake-${request.requestId ?: clock.instant().toEpochMilli()}-$requestedTool",
                            name = requestedTool,
                            argumentsJson =
                                buildJsonObject {
                                    put("command", lastUserMessage)
                                },
                        ),
                    ),
            )
        }
        return ModelResponse(
            text =
                buildString {
                    appendLine("FakeProvider [$timestamp]")
                    appendLine("Session: ${request.sessionId}")
                    appendLine("Request: ${request.requestId ?: "none"}")
                    appendLine("Run mode: ${request.runMode.name.lowercase()}")
                    appendLine("Skills: $skills")
                    appendLine("Tools: $tools")
                    latestToolResult?.let { toolResult ->
                        appendLine("Tool result: ${toolResult.content}")
                    }
                    append("Reply: $lastUserMessage")
                },
        )
    }

    companion object {
        private val TOOL_MARKER = Regex("""\[tool:([A-Za-z0-9._-]+)]""")
    }
}
