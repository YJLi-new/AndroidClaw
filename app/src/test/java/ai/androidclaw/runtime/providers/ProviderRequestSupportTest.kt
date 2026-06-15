package ai.androidclaw.runtime.providers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequestSupportTest {
    @Test
    fun `provider request bounds ids text and history before serialization`() {
        val oversizedId = "id-" + "x".repeat(MAX_PROVIDER_REQUEST_ID_CHARS) + "ID_TAIL"
        val oversizedText = "t".repeat(MAX_PROVIDER_REQUEST_TEXT_CHARS) + "TEXT_TAIL"
        val request =
            buildRequest(
                sessionId = oversizedId,
                requestId = oversizedId,
                systemPrompt = oversizedText,
                messageHistory =
                    (1..(MAX_PROVIDER_REQUEST_MESSAGES + 1)).map { index ->
                        ModelMessage(
                            role = ModelMessageRole.User,
                            content = if (index == MAX_PROVIDER_REQUEST_MESSAGES + 1) oversizedText else "message-$index",
                        )
                    },
            )

        val bounded = request.toBoundedProviderRequest(providerName = "Provider")

        assertEquals(MAX_PROVIDER_REQUEST_ID_CHARS, bounded.sessionId.length)
        assertEquals(MAX_PROVIDER_REQUEST_ID_CHARS, bounded.requestId?.length)
        assertFalse(bounded.sessionId.contains("ID_TAIL"))
        assertFalse(bounded.requestId.orEmpty().contains("ID_TAIL"))
        assertEquals(MAX_PROVIDER_REQUEST_TEXT_CHARS, bounded.systemPrompt.length)
        assertFalse(bounded.systemPrompt.contains("TEXT_TAIL"))
        assertEquals(MAX_PROVIDER_REQUEST_MESSAGES, bounded.messageHistory.size)
        assertFalse(bounded.messageHistory.any { it.content == "message-1" })
        assertEquals(MAX_PROVIDER_REQUEST_TEXT_CHARS, bounded.messageHistory.last().content.length)
        assertFalse(bounded.messageHistory.last().content.contains("TEXT_TAIL"))
    }

    @Test
    fun `provider request bounds outbound transcript tool call metadata`() {
        val oversizedId = "call-" + "x".repeat(MAX_PROVIDER_TOOL_CALL_ID_CHARS) + "ID_TAIL"
        val oversizedName = "tool." + "n".repeat(MAX_PROVIDER_TOOL_CALL_NAME_CHARS) + "NAME_TAIL"
        val request =
            buildRequest(
                messageHistory =
                    listOf(
                        ModelMessage(
                            role = ModelMessageRole.Assistant,
                            content = "",
                            toolCallId = oversizedId,
                            toolName = oversizedName,
                            toolCalls =
                                listOf(
                                    ProviderToolCall(
                                        id = oversizedId,
                                        name = oversizedName,
                                        argumentsJson = buildJsonObject { put("scope", "summary") },
                                    ),
                                ),
                        ),
                    ),
            )

        val boundedToolMessage =
            request
                .toBoundedProviderRequest(providerName = "Provider")
                .messageHistory
                .single()

        assertEquals(MAX_PROVIDER_TOOL_CALL_ID_CHARS, boundedToolMessage.toolCallId?.length)
        assertEquals(MAX_PROVIDER_TOOL_CALL_NAME_CHARS, boundedToolMessage.toolName?.length)
        assertEquals(MAX_PROVIDER_TOOL_CALL_ID_CHARS, boundedToolMessage.toolCalls.single().id.length)
        assertEquals(MAX_PROVIDER_TOOL_CALL_NAME_CHARS, boundedToolMessage.toolCalls.single().name.length)
        assertFalse(boundedToolMessage.toolCallId.orEmpty().contains("ID_TAIL"))
        assertFalse(boundedToolMessage.toolName.orEmpty().contains("NAME_TAIL"))
        assertFalse(boundedToolMessage.toolCalls.single().id.contains("ID_TAIL"))
        assertFalse(boundedToolMessage.toolCalls.single().name.contains("NAME_TAIL"))
    }

    @Test
    fun `provider request rejects oversized outbound tool arguments`() {
        val request =
            buildRequest(
                messageHistory =
                    listOf(
                        ModelMessage(
                            role = ModelMessageRole.Assistant,
                            content = "",
                            toolCalls =
                                listOf(
                                    ProviderToolCall(
                                        id = "call-1",
                                        name = "health.status",
                                        argumentsJson =
                                            buildJsonObject {
                                                put("payload", "x".repeat(MAX_PROVIDER_TOOL_ARGUMENT_CHARS + 1))
                                            },
                                    ),
                                ),
                        ),
                    ),
            )

        val error =
            runCatching {
                request.toBoundedProviderRequest(providerName = "Provider")
            }.exceptionOrNull()

        assertTrue(error is ModelProviderException)
        assertEquals("Provider request included oversized tool arguments.", (error as ModelProviderException).userMessage)
    }

    private fun buildRequest(
        sessionId: String = "session-1",
        requestId: String? = "request-1",
        systemPrompt: String = "system",
        messageHistory: List<ModelMessage> =
            listOf(
                ModelMessage(
                    role = ModelMessageRole.User,
                    content = "hello",
                ),
            ),
    ): ModelRequest =
        ModelRequest(
            sessionId = sessionId,
            requestId = requestId,
            messageHistory = messageHistory,
            systemPrompt = systemPrompt,
            enabledSkills = emptyList(),
            toolDescriptors = emptyList(),
            runMode = ModelRunMode.Interactive,
        )
}
