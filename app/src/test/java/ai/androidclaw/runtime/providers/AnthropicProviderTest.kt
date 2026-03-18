package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.testutil.InMemoryProviderSecretStore
import ai.androidclaw.testutil.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AnthropicProviderTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var secretStore: InMemoryProviderSecretStore
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() = runTest {
        settingsDataStore = SettingsDataStore(
            ApplicationProvider.getApplicationContext(),
        )
        secretStore = InMemoryProviderSecretStore()
        server = MockWebServer()
        server.start()
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot()
                .withEndpointSettings(
                    ProviderType.Anthropic,
                    ProviderEndpointSettings(
                        baseUrl = server.url("/v1/").toString().removeSuffix("/"),
                        modelId = "claude-sonnet-4-5",
                        timeoutSeconds = 1,
                    ),
                )
                .copy(providerType = ProviderType.Anthropic),
        )
        secretStore.writeApiKey(ProviderType.Anthropic, "anth-test")
    }

    @After
    fun tearDown() = runTest {
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        secretStore.clear()
        server.shutdown()
    }

    @Test
    fun `batch response parses text and tool use and serializes request`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "msg_123",
                      "model": "claude-sonnet-4-5",
                      "usage": {
                        "input_tokens": 18,
                        "output_tokens": 7
                      },
                      "content": [
                        { "type": "text", "text": "Checking... " },
                        {
                          "type": "tool_use",
                          "id": "toolu_1",
                          "name": "health.status",
                          "input": { "verbose": true }
                        }
                      ],
                      "stop_reason": "tool_use"
                    }
                    """.trimIndent(),
                ),
        )

        val response = buildProvider().generate(buildRequest())
        val recordedRequest = server.takeRequest(5, TimeUnit.SECONDS)
            ?: error("Expected provider request.")
        val payload = json.parseToJsonElement(recordedRequest.body.readUtf8()).jsonObject
        val messages = payload.getValue("messages").jsonArray

        assertEquals("Checking...", response.text)
        assertEquals("tool_use", response.finishReason)
        assertEquals("msg_123", response.providerRequestId)
        assertEquals("claude-sonnet-4-5", response.modelId)
        assertEquals(18, response.usage?.inputTokens)
        assertEquals(7, response.usage?.outputTokens)
        assertEquals(25, response.usage?.totalTokens)
        assertEquals(1, response.toolCalls.size)
        assertEquals("toolu_1", response.toolCalls.single().id)
        assertEquals("/v1/messages", recordedRequest.path)
        assertEquals("anth-test", recordedRequest.getHeader("x-api-key"))
        assertEquals("2023-06-01", recordedRequest.getHeader("anthropic-version"))
        assertEquals("0.1.0", recordedRequest.getHeader("X-AndroidClaw-Version"))
        assertEquals("AndroidClaw/0.1.0 (ai.androidclaw.app)", recordedRequest.getHeader("User-Agent"))
        assertEquals("claude-sonnet-4-5", payload.getValue("model").jsonPrimitive.content)
        assertTrue(messages.isNotEmpty())
    }

    @Test
    fun `streaming aggregates text and tool deltas into final response`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_stream","model":"claude-sonnet-4-5","usage":{"input_tokens":11,"output_tokens":1}}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello "}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_stream","name":"health.status"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"verbose\":true}"}}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """.trimIndent(),
                ),
        )

        val events = buildProvider().streamGenerate(buildRequest()).toList()

        assertTrue(events.any { it is ModelStreamEvent.TextDelta && it.text == "Hello " })
        assertTrue(
            events.any {
                it is ModelStreamEvent.ToolCallDelta &&
                    it.idPart == "toolu_stream"
            },
        )
        assertTrue(
            events.any {
                it is ModelStreamEvent.Completed &&
                    it.response.finishReason == "tool_use" &&
                    it.response.toolCalls.single().name == "health.status"
            },
        )
        val completed = events.last() as ModelStreamEvent.Completed
        assertEquals("claude-sonnet-4-5", completed.response.modelId)
        assertEquals(11, completed.response.usage?.inputTokens)
        assertEquals(5, completed.response.usage?.outputTokens)
        assertEquals(16, completed.response.usage?.totalTokens)
    }

    @Test
    fun `authentication failure maps to provider authentication error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "error": {
                        "message": "invalid x-api-key"
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val error = assertProviderException {
            buildProvider().generate(buildRequest())
        }

        assertEquals(ModelProviderFailureKind.Authentication, error.kind)
        assertEquals("Provider authentication failed.", error.userMessage)
    }

    @Test
    fun `http 500 maps to provider server failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "error": {
                        "message": "anthropic overloaded"
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val error = assertProviderException {
            buildProvider().generate(buildRequest())
        }

        assertEquals(ModelProviderFailureKind.Server, error.kind)
        assertTrue(error.details.orEmpty().contains("anthropic overloaded"))
    }

    @Test
    fun `stream ending before message stop maps to stream interrupted`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_stream"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello "}}

                    """.trimIndent(),
                ),
        )

        val error = assertProviderException {
            buildProvider().streamGenerate(buildRequest()).toList()
        }

        assertEquals(ModelProviderFailureKind.StreamInterrupted, error.kind)
        assertEquals("Provider stream was interrupted before completion.", error.userMessage)
    }

    @Test
    fun `streamGenerate can be cancelled after the first anthropic delta`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_stream_cancel","model":"claude-sonnet-4-5","usage":{"input_tokens":11,"output_tokens":1}}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

                    """.trimIndent(),
                )
                .setSocketPolicy(SocketPolicy.KEEP_OPEN),
        )

        val events = buildProvider().streamGenerate(buildRequest()).take(1).toList()

        assertEquals(listOf(ModelStreamEvent.TextDelta("Hel")), events)
    }

    private fun buildProvider(): AnthropicProvider {
        return AnthropicProvider(
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
            baseHttpClient = createProviderBaseHttpClient(),
            json = json,
        )
    }

    private fun buildRequest(
        messageHistory: List<ModelMessage> = listOf(
            ModelMessage(
                role = ModelMessageRole.User,
                content = "hello",
            ),
            ModelMessage(
                role = ModelMessageRole.Assistant,
                content = "previous tool call",
                toolCalls = listOf(
                    ProviderToolCall(
                        id = "toolu_prev",
                        name = "health.status",
                        argumentsJson = buildJsonObject {
                            put("verbose", true)
                        },
                    ),
                ),
            ),
            ModelMessage(
                role = ModelMessageRole.Tool,
                content = "{\"ok\":true}",
                toolCallId = "toolu_prev",
                toolName = "health.status",
            ),
        ),
    ): ModelRequest {
        return ModelRequest(
            sessionId = "session-1",
            requestId = "req-123",
            messageHistory = messageHistory,
            systemPrompt = "system prompt",
            enabledSkills = emptyList(),
            toolDescriptors = listOf(
                ai.androidclaw.runtime.tools.ToolDescriptor(
                    name = "health.status",
                    description = "Check health",
                    arguments = emptyList(),
                    inputSchema = buildJsonObject {},
                ),
            ),
            runMode = ModelRunMode.Interactive,
        )
    }

    private suspend fun assertProviderException(
        block: suspend () -> Unit,
    ): ModelProviderException {
        return try {
            block()
            error("Expected ModelProviderException.")
        } catch (error: ModelProviderException) {
            error
        }
    }
}
