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
 * `[tool:image_generate]` without JSON uses the rest of the message as the image prompt.
 *
 * Search trigger: `[search:<query>]` answers like a hosted web search, with the query and two
 * fixed sources on reserved example domains, so the Sources row can be shown offline.
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
        val lastUserImages =
            request.messageHistory
                .getOrNull(lastUserIndex)
                ?.attachments
                .orEmpty()
                .filter { it.file != null }
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
                                    ?: scriptedArguments(requestedTool, lastUserMessage, marker.value),
                        ),
                    ),
            )
        }
        val searchQuery =
            SEARCH_MARKER
                .find(lastUserMessage)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.take(MAX_SEARCH_QUERY_CHARS)
        return ModelResponse(
            webSearchQueries = listOfNotNull(searchQuery),
            webSources = searchQuery?.let(::scriptedSources).orEmpty(),
            text =
                buildString {
                    if (latestToolResult != null) {
                        // After a tool round trip the demo reply is one readable line about the
                        // result; the tool card already shows the details.
                        append("Done with ").append(requestedTool).append(": ")
                        append(
                            latestToolResult.content
                                .lineSequence()
                                .firstOrNull()
                                .orEmpty()
                                .take(MAX_TOOL_ECHO_CHARS),
                        )
                    } else if (lastUserMessage.isBlank() && lastUserImages.isEmpty()) {
                        append("Hello from the offline fake provider.")
                    } else {
                        append("Reply: ").append(lastUserMessage.take(MAX_ECHO_CHARS))
                    }
                    if (latestToolResult == null && lastUserImages.isNotEmpty()) {
                        // Deterministic image reply: count and pixel sizes of the images sent.
                        if (lastUserMessage.isNotBlank()) append('\n')
                        append("Images: ").append(lastUserImages.size).append(" (")
                        append(lastUserImages.joinToString { "${it.width}×${it.height}" })
                        append(")")
                    }
                    if (request.runMode == ModelRunMode.Scheduled) {
                        append("\nRun mode: scheduled.")
                    }
                },
        )
    }

    /**
     * Scripted image path: `Draw a red fox [tool:image_generate]` calls image_generate with the
     * message (marker removed) as the prompt; the debug fake image backend answers offline.
     */
    private fun scriptedArguments(
        toolName: String,
        message: String,
        marker: String,
    ): JsonObject =
        if (toolName == "image_generate") {
            buildJsonObject { put("prompt", message.replace(marker, "").trim().ifEmpty { "A test image" }) }
        } else {
            buildJsonObject { put("command", message) }
        }

    private fun scriptedSources(query: String): List<WebSource> {
        val slug =
            query
                .lowercase()
                .replace(NON_SLUG_CHARS, "-")
                .trim('-')
                .ifEmpty { "search" }
        return listOf(
            WebSource(url = "https://example.com/$slug", title = "Example result for $query"),
            WebSource(url = "https://example.org/$slug", title = "Second example result"),
        )
    }

    private fun String.toJsonObjectOrNull(): JsonObject? =
        takeIf(String::isNotBlank)?.let { raw ->
            runCatching { FAKE_JSON.parseToJsonElement(raw).jsonObject }.getOrNull()
        }

    companion object {
        // Android's ICU regex engine rejects unescaped `}` and `]` that the JVM accepts.
        private val TOOL_MARKER = Regex("""\[tool:([A-Za-z0-9._-]+)(?:\s+(\{.*?\}))?\]""")
        private val SEARCH_MARKER = Regex("""\[search:([^\]]+)\]""")
        private val NON_SLUG_CHARS = Regex("[^a-z0-9]+")
        private val FAKE_JSON = Json { ignoreUnknownKeys = true }
        private const val MAX_ECHO_CHARS = 500
        private const val MAX_TOOL_ECHO_CHARS = 300
        private const val MAX_SEARCH_QUERY_CHARS = 120
    }
}
