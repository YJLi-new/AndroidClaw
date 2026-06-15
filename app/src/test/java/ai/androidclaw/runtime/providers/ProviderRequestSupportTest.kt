package ai.androidclaw.runtime.providers

import ai.androidclaw.runtime.tools.ToolDescriptor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
        val lastMessage = bounded.messageHistory.last()

        assertEquals(MAX_PROVIDER_REQUEST_ID_CHARS, bounded.sessionId.length)
        assertEquals(MAX_PROVIDER_REQUEST_ID_CHARS, bounded.requestId?.length)
        assertFalse(bounded.sessionId.contains("ID_TAIL"))
        assertFalse(bounded.requestId.orEmpty().contains("ID_TAIL"))
        assertEquals(MAX_PROVIDER_REQUEST_TEXT_CHARS, bounded.systemPrompt.length)
        assertFalse(bounded.systemPrompt.contains("TEXT_TAIL"))
        assertEquals(MAX_PROVIDER_REQUEST_MESSAGES, bounded.messageHistory.size)
        assertFalse(bounded.messageHistory.any { it.content == "message-1" })
        assertEquals(MAX_PROVIDER_REQUEST_TEXT_CHARS, lastMessage.content.length)
        assertFalse(lastMessage.content.contains("TEXT_TAIL"))
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
        val boundedCall = boundedToolMessage.toolCalls.single()

        assertEquals(MAX_PROVIDER_TOOL_CALL_ID_CHARS, boundedToolMessage.toolCallId?.length)
        assertEquals(MAX_PROVIDER_TOOL_CALL_NAME_CHARS, boundedToolMessage.toolName?.length)
        assertEquals(MAX_PROVIDER_TOOL_CALL_ID_CHARS, boundedCall.id.length)
        assertEquals(MAX_PROVIDER_TOOL_CALL_NAME_CHARS, boundedCall.name.length)
        assertFalse(boundedToolMessage.toolCallId.orEmpty().contains("ID_TAIL"))
        assertFalse(boundedToolMessage.toolName.orEmpty().contains("NAME_TAIL"))
        assertFalse(boundedCall.id.contains("ID_TAIL"))
        assertFalse(boundedCall.name.contains("NAME_TAIL"))
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

    @Test
    fun `provider request bounds tool descriptor metadata before serialization`() {
        val oversizedDescription = "d".repeat(MAX_PROVIDER_REQUEST_TOOL_DESCRIPTION_CHARS) + "DESCRIPTION_TAIL"
        val oversizedAlias = "a".repeat(MAX_PROVIDER_REQUEST_TOOL_ALIAS_CHARS) + "ALIAS_TAIL"
        val oversizedSchemaText = "s".repeat(MAX_PROVIDER_REQUEST_TOOL_SCHEMA_STRING_CHARS) + "SCHEMA_TAIL"
        val request =
            buildRequest(
                toolDescriptors =
                    (1..(MAX_PROVIDER_REQUEST_TOOLS + 1)).map { index ->
                        ToolDescriptor(
                            name = "tool.$index",
                            description = if (index == 1) oversizedDescription else "Tool $index",
                            aliases = if (index == 1) listOf(oversizedAlias) else emptyList(),
                            inputSchema =
                                buildJsonObject {
                                    put("type", "object")
                                    put("description", oversizedSchemaText)
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            repeat(MAX_PROVIDER_REQUEST_TOOL_SCHEMA_ENTRIES + 1) { enumIndex ->
                                                add(JsonPrimitive("choice-$enumIndex"))
                                            }
                                        },
                                    )
                                },
                        )
                    },
            )

        val boundedDescriptors =
            request
                .toBoundedProviderRequest(providerName = "Provider")
                .toolDescriptors
        val firstDescriptor = boundedDescriptors.first()
        val firstSchema = firstDescriptor.inputSchema
        val boundedSchemaDescription =
            firstSchema
                .getValue("description")
                .jsonPrimitive
                .content
        val boundedSchemaEnum =
            firstSchema
                .getValue("enum")
                .jsonArray

        assertEquals(MAX_PROVIDER_REQUEST_TOOLS, boundedDescriptors.size)
        assertFalse(boundedDescriptors.any { it.name == "tool.${MAX_PROVIDER_REQUEST_TOOLS + 1}" })
        assertEquals(MAX_PROVIDER_REQUEST_TOOL_DESCRIPTION_CHARS, firstDescriptor.description.length)
        assertFalse(firstDescriptor.description.contains("DESCRIPTION_TAIL"))
        assertEquals(MAX_PROVIDER_REQUEST_TOOL_ALIAS_CHARS, firstDescriptor.aliases.single().length)
        assertFalse(firstDescriptor.aliases.single().contains("ALIAS_TAIL"))
        assertEquals(
            MAX_PROVIDER_REQUEST_TOOL_SCHEMA_STRING_CHARS,
            boundedSchemaDescription.length,
        )
        assertFalse(boundedSchemaDescription.contains("SCHEMA_TAIL"))
        assertEquals(MAX_PROVIDER_REQUEST_TOOL_SCHEMA_ENTRIES, boundedSchemaEnum.size)
        assertFalse(
            boundedSchemaEnum.any { value ->
                value.jsonPrimitive.content == "choice-$MAX_PROVIDER_REQUEST_TOOL_SCHEMA_ENTRIES"
            },
        )
    }

    @Test
    fun `provider request rejects oversized tool descriptor names`() {
        val request =
            buildRequest(
                toolDescriptors =
                    listOf(
                        ToolDescriptor(
                            name = "t".repeat(MAX_PROVIDER_TOOL_CALL_NAME_CHARS + 1),
                            description = "Oversized tool name",
                        ),
                    ),
            )

        val error =
            runCatching {
                request.toBoundedProviderRequest(providerName = "Provider")
            }.exceptionOrNull()

        assertTrue(error is ModelProviderException)
        assertEquals(
            "Provider request included an oversized tool descriptor name.",
            (error as ModelProviderException).userMessage,
        )
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
        toolDescriptors: List<ToolDescriptor> = emptyList(),
    ): ModelRequest =
        ModelRequest(
            sessionId = sessionId,
            requestId = requestId,
            messageHistory = messageHistory,
            systemPrompt = systemPrompt,
            enabledSkills = emptyList(),
            toolDescriptors = toolDescriptors,
            runMode = ModelRunMode.Interactive,
        )
}
