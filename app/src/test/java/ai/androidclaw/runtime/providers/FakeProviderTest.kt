package ai.androidclaw.runtime.providers

import ai.androidclaw.runtime.tools.ToolDescriptor
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeProviderTest {
    private val provider = FakeProvider(
        clock = Clock.fixed(
            Instant.parse("2026-03-08T00:00:00Z"),
            ZoneOffset.UTC,
        ),
    )

    @Test
    fun `plain request returns terminal response`() = runTest {
        val response = provider.generate(
            request(
                messageHistory = listOf(
                    ModelMessage(
                        role = ModelMessageRole.User,
                        content = "hello",
                    ),
                ),
            ),
        )

        assertEquals("stop", response.finishReason)
        assertTrue(response.text.contains("Reply: hello"))
    }

    @Test
    fun `tool marker returns tool use response`() = runTest {
        val response = provider.generate(
            request(
                messageHistory = listOf(
                    ModelMessage(
                        role = ModelMessageRole.User,
                        content = "check [tool:health.status]",
                    ),
                ),
            ),
        )

        assertEquals("tool_use", response.finishReason)
        assertEquals(listOf("health.status"), response.toolCalls.map { it.name })
    }

    @Test
    fun `tool result follow up returns terminal response`() = runTest {
        val response = provider.generate(
            request(
                messageHistory = listOf(
                    ModelMessage(
                        role = ModelMessageRole.User,
                        content = "check [tool:health.status]",
                    ),
                    ModelMessage(
                        role = ModelMessageRole.Tool,
                        content = "Tool result: Health okay",
                        toolCallId = "call-1",
                        toolName = "health.status",
                    ),
                ),
            ),
        )

        assertEquals("stop", response.finishReason)
        assertTrue(response.text.contains("Tool result: Tool result: Health okay"))
    }

    @Test
    fun `malformed tool marker falls back to plain response`() = runTest {
        val response = provider.generate(
            request(
                messageHistory = listOf(
                    ModelMessage(
                        role = ModelMessageRole.User,
                        content = "check [tool:]",
                    ),
                ),
            ),
        )

        assertEquals("stop", response.finishReason)
        assertTrue(response.toolCalls.isEmpty())
    }

    private fun request(messageHistory: List<ModelMessage>): ModelRequest {
        return ModelRequest(
            sessionId = "session-1",
            requestId = "request-1",
            messageHistory = messageHistory,
            systemPrompt = "prompt",
            enabledSkills = emptyList(),
            toolDescriptors = listOf(
                ToolDescriptor(
                    name = "health.status",
                    description = "Health check",
                    inputSchema = buildJsonObject {
                        put("type", "object")
                    },
                ),
            ),
            runMode = ModelRunMode.Interactive,
        )
    }
}
