package ai.androidclaw.runtime.providers

import ai.androidclaw.runtime.tools.ToolDescriptor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FakeProviderTest {
    private val provider =
        FakeProvider(
            clock =
                Clock.fixed(
                    Instant.parse("2026-03-08T00:00:00Z"),
                    ZoneOffset.UTC,
                ),
        )

    @Test
    fun `plain request returns terminal response`() =
        runTest {
            val response =
                provider.generate(
                    request(
                        messageHistory =
                            listOf(
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
    fun `tool marker returns tool use response`() =
        runTest {
            val response =
                provider.generate(
                    request(
                        messageHistory =
                            listOf(
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
    fun `tool result follow up returns terminal response`() =
        runTest {
            val response =
                provider.generate(
                    request(
                        messageHistory =
                            listOf(
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
            assertEquals("Done with health.status: Tool result: Health okay", response.text)
        }

    @Test
    fun `malformed tool marker falls back to plain response`() =
        runTest {
            val response =
                provider.generate(
                    request(
                        messageHistory =
                            listOf(
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

    @Test
    fun `plain replies are short and carry no internal ids`() =
        runTest {
            val response =
                provider.generate(
                    request(messageHistory = listOf(ModelMessage(role = ModelMessageRole.User, content = "hello"))),
                )

            assertEquals("Reply: hello", response.text)
            assertTrue(response.webSearchQueries.isEmpty())
            assertTrue(response.webSources.isEmpty())
        }

    @Test
    fun `search marker returns hosted style query and sources`() =
        runTest {
            val response =
                provider.generate(
                    request(
                        messageHistory =
                            listOf(ModelMessage(role = ModelMessageRole.User, content = "News? [search:Android 17 release]")),
                    ),
                )

            assertEquals("stop", response.finishReason)
            assertEquals(listOf("Android 17 release"), response.webSearchQueries)
            assertEquals(
                listOf("https://example.com/android-17-release", "https://example.org/android-17-release"),
                response.webSources.map { it.url },
            )
            assertEquals("Example result for Android 17 release", response.webSources.first().title)
        }

    @Test
    fun `tool marker accepts json arguments`() =
        runTest {
            val response =
                provider.generate(
                    request(
                        messageHistory =
                            listOf(ModelMessage(role = ModelMessageRole.User, content = """[tool:set_timer {"seconds":60}]""")),
                    ),
                )

            assertEquals("set_timer", response.toolCalls.single().name)
            assertEquals(
                "60",
                response.toolCalls
                    .single()
                    .argumentsJson["seconds"]
                    .toString(),
            )
        }

    @Test
    fun `tool results from earlier turns do not satisfy a new marker`() =
        runTest {
            val response =
                provider.generate(
                    request(
                        messageHistory =
                            listOf(
                                ModelMessage(role = ModelMessageRole.User, content = "[tool:health.status]"),
                                ModelMessage(
                                    role = ModelMessageRole.Tool,
                                    content = "old",
                                    toolCallId = "call-1",
                                    toolName = "health.status",
                                ),
                                ModelMessage(role = ModelMessageRole.Assistant, content = "Reply"),
                                ModelMessage(role = ModelMessageRole.User, content = "again [tool:health.status]"),
                            ),
                    ),
                )

            assertEquals("tool_use", response.finishReason)
        }

    private fun request(messageHistory: List<ModelMessage>): ModelRequest =
        ModelRequest(
            sessionId = "session-1",
            requestId = "request-1",
            messageHistory = messageHistory,
            systemPrompt = "prompt",
            enabledSkills = emptyList(),
            toolDescriptors =
                listOf(
                    ToolDescriptor(
                        name = "health.status",
                        description = "Health check",
                        inputSchema =
                            buildJsonObject {
                                put("type", "object")
                            },
                    ),
                ),
            runMode = ModelRunMode.Interactive,
        )
}
