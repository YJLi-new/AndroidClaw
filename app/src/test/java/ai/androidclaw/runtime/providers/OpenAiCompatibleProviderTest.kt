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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            ProviderSettingsSnapshot(
                providerType = ProviderType.OpenAiCompatible,
                openAiBaseUrl = server.url("/v1/").toString().removeSuffix("/"),
                openAiModelId = "gpt-test",
                openAiTimeoutSeconds = 1,
            ),
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
    fun `missing api key fails with configuration error`() = runTest {
        secretStore.writeApiKey(ProviderType.OpenAiCompatible, null)

        val error = assertProviderException {
            buildProvider().generate(buildRequest())
        }

        assertEquals(ModelProviderFailureKind.Configuration, error.kind)
        assertEquals("Provider API key is required.", error.userMessage)
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

    private fun buildProvider(): OpenAiCompatibleProvider {
        return OpenAiCompatibleProvider(
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
            baseHttpClient = OkHttpClient(),
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
