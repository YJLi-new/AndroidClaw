package ai.androidclaw.runtime.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Clock

/**
 * Offline provider for first launch, debug builds and tests. Replies are short, readable and
 * deterministic.
 *
 * Tool trigger: a user message containing `[tool:<name>]` makes the provider call that tool once,
 * e.g. `What is my battery? [tool:device_status]`. Optional JSON arguments may follow the name:
 * `[tool:set_timer {"seconds":60}]`. Without JSON the call receives `{"command": <message>}`.
 * After the tool result arrives the provider answers with the first line of that result.
 */
class FakeProvider(
    private val clock: Clock,
) : ModelProvider {
    override val id: String = "fake"

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val lastUserIndex = request.messageHistory.indexOfLast { it.role == ModelMessageRole.User }
        val lastUserMessage =
            request.messageHistory
                .getOrNull(lastUserIndex)
                ?.content
                ?.trim()
                .orEmpty()
        val marker = TOOL_MARKER.find(lastUserMessage)
        val requestedTool =
            marker
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf(String::isNotBlank)
        val latestToolResult =
            requestedTool?.let { toolName ->
                request.messageHistory
                    .drop(lastUserIndex + 1)
                    .lastOrNull { message ->
                        message.role == ModelMessageRole.Tool &&
                            message.toolName == toolName &&
                            message.toolCallId != null &&
                            message.toolCalls.isEmpty()
                    }
            }
        if (marker != null && requestedTool != null && latestToolResult == null) {
            return ModelResponse(
                text = "Calling $requestedTool.",
                finishReason = "tool_use",
                toolCalls =
                    listOf(
                        ProviderToolCall(
                            id = "fake-${request.requestId ?: clock.instant().toEpochMilli()}-$requestedTool",
                            name = requestedTool,
                            argumentsJson =
                                marker.groupValues.getOrNull(2)?.toJsonObjectOrNull()
                                    ?: buildJsonObject { put("command", lastUserMessage) },
                        ),
                    ),
            )
        }
        return ModelResponse(
            text =
                buildString {
                    if (latestToolResult != null) {
                        append("Tool result: ")
                        append(
                            latestToolResult.content
                                .lineSequence()
                                .firstOrNull()
                                .orEmpty()
                                .take(MAX_TOOL_ECHO_CHARS),
                        )
                        append('\n')
                    }
                    if (lastUserMessage.isBlank()) {
                        append("Hello from the offline fake provider.")
                    } else {
                        append("Reply: ").append(lastUserMessage.take(MAX_ECHO_CHARS))
                    }
                    if (request.runMode == ModelRunMode.Scheduled) {
                        append("\nRun mode: scheduled.")
                    }
                },
        )
    }

    private fun String.toJsonObjectOrNull(): JsonObject? =
        takeIf(String::isNotBlank)?.let { raw ->
            runCatching { FAKE_JSON.parseToJsonElement(raw).jsonObject }.getOrNull()
        }

    companion object {
        private val TOOL_MARKER = Regex("""\[tool:([A-Za-z0-9._-]+)(?:\s+(\{.*?}))?]""")
        private val FAKE_JSON = Json { ignoreUnknownKeys = true }
        private const val MAX_ECHO_CHARS = 500
        private const val MAX_TOOL_ECHO_CHARS = 300
    }
}
