package ai.androidclaw.runtime.providers

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
import kotlinx.serialization.json.boolean
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
class OpenAiCompatibleProviderTest {
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
                    ProviderType.OpenAiCompatible,
                    ai.androidclaw.data.ProviderEndpointSettings(
                        baseUrl = server.url("/v1/").toString().removeSuffix("/"),
                        modelId = "gpt-test",
                        timeoutSeconds = 1,
                    ),
                )
                .copy(providerType = ProviderType.OpenAiCompatible),
        )
        secretStore.writeApiKey(ProviderType.OpenAiCompatible, "sk-test")
    }

    @After
    fun tearDown() = runTest {
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        secretStore.clear()
        server.shutdown()
    }

    @Test
    fun `successful response is parsed and request is formed correctly`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "resp-123",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "Hello from provider"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val response = buildProvider().generate(buildRequest())
        val recordedRequest = server.takeRequest(5, TimeUnit.SECONDS)
            ?: error("Expected provider request.")
        val payload = json.parseToJsonElement(recordedRequest.body.readUtf8()).jsonObject
        val messages = payload.getValue("messages").jsonArray

        assertEquals("Hello from provider", response.text)
        assertEquals("resp-123", response.providerRequestId)
        assertEquals("/v1/chat/completions", recordedRequest.path)
        assertEquals("Bearer sk-test", recordedRequest.getHeader("Authorization"))
        assertEquals("req-123", recordedRequest.getHeader("X-Request-Id"))
        assertEquals("0.1.0", recordedRequest.getHeader("X-AndroidClaw-Version"))
        assertEquals("AndroidClaw/0.1.0 (ai.androidclaw.app)", recordedRequest.getHeader("User-Agent"))
        assertEquals("gpt-test", payload.getValue("model").jsonPrimitive.content)
        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("system prompt", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("hello", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `tool definitions and transcript tool calls are serialized for openai requests`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "resp-structured",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "Structured reply"
                          },
                          "finish_reason": "stop"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        buildProvider().generate(
            buildRequest(
                messageHistory = listOf(
                    ModelMessage(
                        role = ModelMessageRole.User,
                        content = "hello",
                    ),
                    ModelMessage(
                        role = ModelMessageRole.Assistant,
                        content = "",
                        toolCalls = listOf(
                            ProviderToolCall(
                                id = "call-1",
                                name = "health.status",
                                argumentsJson = buildJsonObject {
                                    put("scope", "summary")
                                },
                            ),
                        ),
                    ),
                    ModelMessage(
                        role = ModelMessageRole.Tool,
                        content = "Health ok",
                        toolCallId = "call-1",
                    ),
                ),
                toolDescriptors = listOf(
                    ai.androidclaw.runtime.tools.ToolDescriptor(
                        name = "health.status",
                        description = "Report health",
                        inputSchema = buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "scope",
                                        buildJsonObject {
                                            put("type", "string")
                                        },
                                    )
                                },
                            )
                        },
                    ),
                ),
            ),
        )

        val recordedRequest = server.takeRequest(5, TimeUnit.SECONDS)
            ?: error("Expected provider request.")
        val payload = json.parseToJsonElement(recordedRequest.body.readUtf8()).jsonObject
        val tools = payload.getValue("tools").jsonArray
        val messages = payload.getValue("messages").jsonArray
        val assistantToolCall = messages[2].jsonObject.getValue("tool_calls").jsonArray.single().jsonObject
        val toolMessage = messages[3].jsonObject

        assertEquals("health.status", tools.single().jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("health.status", assistantToolCall.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("{\"scope\":\"summary\"}", assistantToolCall.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
        assertEquals("tool", toolMessage.getValue("role").jsonPrimitive.content)
        assertEquals("call-1", toolMessage.getValue("tool_call_id").jsonPrimitive.content)
    }

    @Test
    fun `response tool calls are parsed into tool use finish reason`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "resp-tools",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": null,
                            "tool_calls": [
                              {
                                "id": "call-1",
                                "type": "function",
                                "function": {
                                  "name": "health.status",
                                  "arguments": "{\"scope\":\"summary\"}"
                                }
                              }
                            ]
                          },
                          "finish_reason": "tool_calls"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val response = buildProvider().generate(
            buildRequest(
                toolDescriptors = listOf(
                    ai.androidclaw.runtime.tools.ToolDescriptor(
                        name = "health.status",
                        description = "Report health",
                    ),
                ),
            ),
        )

        assertEquals("tool_use", response.finishReason)
        assertEquals("", response.text)
        assertEquals("call-1", response.toolCalls.single().id)
        assertEquals("health.status", response.toolCalls.single().name)
        assertEquals("summary", response.toolCalls.single().argumentsJson.getValue("scope").jsonPrimitive.content)
    }

    @Test
    fun `streamGenerate emits text deltas and final response for sse text streams`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    : keepalive

                    data: {"id":"resp-stream-1","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}

                    data: {"id":"resp-stream-1","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}

                    data: {"id":"resp-stream-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val events = buildProvider().streamGenerate(buildRequest()).toList()
        val recordedRequest = server.takeRequest(5, TimeUnit.SECONDS)
            ?: error("Expected streaming provider request.")
        val payload = json.parseToJsonElement(recordedRequest.body.readUtf8()).jsonObject
        val completed = events.last() as ModelStreamEvent.Completed

        assertEquals("true", payload.getValue("stream").jsonPrimitive.content)
        assertEquals(ModelStreamEvent.TextDelta("Hel"), events[0])
        assertEquals(ModelStreamEvent.TextDelta("lo"), events[1])
        assertEquals("Hello", completed.response.text)
        assertEquals("resp-stream-1", completed.response.providerRequestId)
        assertEquals("stop", completed.response.finishReason)
    }

    @Test
    fun `streamGenerate reconstructs streamed tool call fragments`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"id":"resp-stream-tools","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"health.status","arguments":"{\"scope\""}}]},"finish_reason":null}]}

                    data: {"id":"resp-stream-tools","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"summary\"}"}}]},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val events = buildProvider().streamGenerate(
            buildRequest(
                toolDescriptors = listOf(
                    ai.androidclaw.runtime.tools.ToolDescriptor(
                        name = "health.status",
                        description = "Report health",
                    ),
                ),
            ),
        ).toList()

        val completed = events.last() as ModelStreamEvent.Completed

        assertTrue(events.any { it is ModelStreamEvent.ToolCallDelta })
        assertEquals("tool_use", completed.response.finishReason)
        assertEquals("call-1", completed.response.toolCalls.single().id)
        assertEquals("health.status", completed.response.toolCalls.single().name)
        assertEquals("summary", completed.response.toolCalls.single().argumentsJson.getValue("scope").jsonPrimitive.content)
    }

    @Test
    fun `streamGenerate falls back to batch generate when streaming is unsupported`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(501)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"stream not supported"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "resp-fallback",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "Fallback answer"
                          },
                          "finish_reason": "stop"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val events = buildProvider().streamGenerate(buildRequest()).toList()
        val firstRequest = server.takeRequest(5, TimeUnit.SECONDS)
            ?: error("Expected first provider request.")
        val secondRequest = server.takeRequest(5, TimeUnit.SECONDS)
            ?: error("Expected fallback provider request.")
        val firstPayload = json.parseToJsonElement(firstRequest.body.readUtf8()).jsonObject
        val secondPayload = json.parseToJsonElement(secondRequest.body.readUtf8()).jsonObject
        val completed = events.single() as ModelStreamEvent.Completed

        assertEquals("true", firstPayload.getValue("stream").jsonPrimitive.content)
        assertTrue(secondPayload["stream"] == null)
        assertEquals("Fallback answer", completed.response.text)
        assertEquals("resp-fallback", completed.response.providerRequestId)
    }

    @Test
    fun `malformed sse chunk is mapped to response failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {not-json}

                    """.trimIndent(),
                ),
        )

        val error = assertProviderException {
            buildProvider().streamGenerate(buildRequest()).toList()
        }

        assertEquals(ModelProviderFailureKind.Response, error.kind)
        assertEquals("Provider returned malformed SSE chunk.", error.userMessage)
    }

    @Test
    fun `streamGenerate can be cancelled after the first delta`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"id":"resp-stream-cancel","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}

                    """.trimIndent(),
                )
                .setSocketPolicy(SocketPolicy.KEEP_OPEN),
        )

        val events = buildProvider().streamGenerate(buildRequest()).take(1).toList()

        assertEquals(listOf(ModelStreamEvent.TextDelta("Hel")), events)
    }

    @Test
    fun `missing api key fails with configuration error`() = runTest {
        secretStore.writeApiKey(ProviderType.OpenAiCompatible, null)

        val error = assertProviderException {
            buildProvider().generate(buildRequest())
        }

        assertEquals(ModelProviderFailureKind.Configuration, error.kind)
        assertEquals("Provider API key is required.", error.userMessage)
    }

    @Test
    fun `invalid base url fails with invalid endpoint classification`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot()
                .withEndpointSettings(
                    ProviderType.OpenAiCompatible,
                    ai.androidclaw.data.ProviderEndpointSettings(
                        baseUrl = "not-a-url",
                        modelId = "gpt-test",
                        timeoutSeconds = 5,
                    ),
                )
                .copy(providerType = ProviderType.OpenAiCompatible),
        )

        val error = assertProviderException {
            buildProvider().generate(buildRequest())
        }

        assertEquals(ModelProviderFailureKind.InvalidEndpoint, error.kind)
        assertEquals("Provider base URL is invalid.", error.userMessage)
    }

    @Test
    fun `http 401 is mapped to authentication failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"bad key"}}"""),
        )

        val error = assertProviderException {
            buildProvider().generate(buildRequest())
        }

        assertEquals(ModelProviderFailureKind.Authentication, error.kind)
        assertTrue(error.details.orEmpty().contains("bad key"))
    }

    @Test
    fun `http 500 is mapped to provider server failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"backend overloaded"}}"""),
        )

        val error = assertProviderException {
            buildProvider().generate(buildRequest())
        }

        assertEquals(ModelProviderFailureKind.Server, error.kind)
        assertTrue(error.details.orEmpty().contains("backend overloaded"))
    }

    @Test
    fun `malformed json is mapped to response failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{not-json"),
        )

        val error = assertProviderException {
            buildProvider().generate(buildRequest())
        }

        assertEquals(ModelProviderFailureKind.Response, error.kind)
        assertEquals("Provider returned malformed JSON.", error.userMessage)
    }

    @Test
    fun `timeout is mapped to timeout failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.NO_RESPONSE),
        )

        val error = assertProviderException {
            buildProvider().generate(buildRequest())
        }

        assertEquals(ModelProviderFailureKind.Timeout, error.kind)
        assertEquals("Provider request timed out.", error.userMessage)
    }

    @Test
    fun `stream ending before terminal event is mapped to stream interrupted`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"id":"resp-stream-cut","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}

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
    fun `streaming text chunks are emitted incrementally and complete with the aggregated response`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"id":"resp-stream","choices":[{"delta":{"content":"Hello "}}]}

                    data: {"id":"resp-stream","choices":[{"delta":{"content":"world"}}]}

                    data: {"id":"resp-stream","choices":[{"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val events = buildProvider().streamGenerate(buildRequest()).toList()
        val recordedRequest = server.takeRequest(5, TimeUnit.SECONDS)
            ?: error("Expected provider request.")
        val payload = json.parseToJsonElement(recordedRequest.body.readUtf8()).jsonObject

        assertEquals(true, payload.getValue("stream").jsonPrimitive.boolean)
        assertEquals(
            listOf("Hello ", "world"),
            events.filterIsInstance<ModelStreamEvent.TextDelta>().map { it.text },
        )
        val completed = events.last() as ModelStreamEvent.Completed
        assertEquals("Hello world", completed.response.text)
        assertEquals("resp-stream", completed.response.providerRequestId)
        assertEquals("stop", completed.response.finishReason)
    }

    @Test
    fun `streamed tool call fragments are reassembled into a final tool use response`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"id":"resp-tools","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"health.status","arguments":"{\"scope\":\""}}]}}]}

                    data: {"id":"resp-tools","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"summary\"}"}}]},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val events = buildProvider().streamGenerate(
            buildRequest(
                toolDescriptors = listOf(
                    ai.androidclaw.runtime.tools.ToolDescriptor(
                        name = "health.status",
                        description = "Report health",
                    ),
                ),
            ),
        ).toList()

        assertTrue(events.filterIsInstance<ModelStreamEvent.ToolCallDelta>().isNotEmpty())
        val completed = events.last() as ModelStreamEvent.Completed
        assertEquals("tool_use", completed.response.finishReason)
        assertEquals("call-1", completed.response.toolCalls.single().id)
        assertEquals("health.status", completed.response.toolCalls.single().name)
        assertEquals(
            "summary",
            completed.response.toolCalls.single().argumentsJson.getValue("scope").jsonPrimitive.content,
        )
    }

    @Test
    fun `non sse success response falls back to batch generation`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "ignored-stream-body",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "ignored"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "resp-fallback",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "Fallback batch reply"
                          },
                          "finish_reason": "stop"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val events = buildProvider().streamGenerate(buildRequest()).toList()
        val firstRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Expected first request.")
        val secondRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Expected fallback request.")
        val firstPayload = json.parseToJsonElement(firstRequest.body.readUtf8()).jsonObject
        val secondPayload = json.parseToJsonElement(secondRequest.body.readUtf8()).jsonObject

        assertEquals(true, firstPayload.getValue("stream").jsonPrimitive.boolean)
        assertTrue("stream" !in secondPayload)
        val completed = events.last() as ModelStreamEvent.Completed
        assertEquals("Fallback batch reply", completed.response.text)
        assertEquals("resp-fallback", completed.response.providerRequestId)
    }

    @Test
    fun `malformed sse chunk is mapped to a response failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {not-json}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val error = assertProviderException {
            buildProvider().streamGenerate(buildRequest()).toList()
        }

        assertEquals(ModelProviderFailureKind.Response, error.kind)
        assertEquals("Provider returned malformed SSE chunk.", error.userMessage)
    }

    @Test
    fun `named compatible providers use their own settings and secrets`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot()
                .withEndpointSettings(
                    ProviderType.Gemini,
                    ai.androidclaw.data.ProviderEndpointSettings(
                        baseUrl = server.url("/v1beta/openai").toString().removeSuffix("/"),
                        modelId = "gemini-2.0-flash",
                        timeoutSeconds = 5,
                    ),
                )
                .copy(providerType = ProviderType.Gemini),
        )
        secretStore.writeApiKey(ProviderType.Gemini, "gem-test")
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "resp-gemini",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "Hello from Gemini-compatible path"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val response = buildProvider(ProviderType.Gemini).generate(buildRequest())
        val recordedRequest = server.takeRequest(5, TimeUnit.SECONDS)
            ?: error("Expected provider request.")

        assertEquals("Hello from Gemini-compatible path", response.text)
        assertEquals("/v1beta/openai/chat/completions", recordedRequest.path)
        assertEquals("Bearer gem-test", recordedRequest.getHeader("Authorization"))
    }

    private fun buildProvider(providerType: ProviderType = ProviderType.OpenAiCompatible): OpenAiCompatibleProvider {
        return OpenAiCompatibleProvider(
            providerType = providerType,
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
        ),
        toolDescriptors: List<ai.androidclaw.runtime.tools.ToolDescriptor> = emptyList(),
    ): ModelRequest {
        return ModelRequest(
            sessionId = "session-1",
            requestId = "req-123",
            messageHistory = messageHistory,
            systemPrompt = "system prompt",
            enabledSkills = listOf(
                ModelSkillMetadata(
                    id = "skill-1",
                    name = "demo-skill",
                    description = "Demo",
                    instructions = "Be helpful.",
                ),
            ),
            toolDescriptors = toolDescriptors,
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
