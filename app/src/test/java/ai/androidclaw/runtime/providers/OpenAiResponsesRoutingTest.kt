package ai.androidclaw.runtime.providers

import ai.androidclaw.data.OPENAI_DEFAULT_BASE_URL
import ai.androidclaw.data.OPENAI_DEFAULT_MODEL_ID
import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.testutil.InMemoryProviderSecretStore
import ai.androidclaw.testutil.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Official OpenAI (api.openai.com) is routed to /v1/responses; MockWebServer stands in for it by
 * forcing the routing predicate to true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OpenAiResponsesRoutingTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var secretStore: InMemoryProviderSecretStore
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() =
        runTest {
            settingsDataStore = SettingsDataStore(ApplicationProvider.getApplicationContext())
            secretStore = InMemoryProviderSecretStore()
            server = MockWebServer()
            server.start()
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAi,
                        ProviderEndpointSettings(
                            baseUrl = server.url("/v1/").toString().removeSuffix("/"),
                            modelId = "gpt-6-luna",
                            timeoutSeconds = 5,
                        ),
                    ).copy(providerType = ProviderType.OpenAi),
            )
            secretStore.writeApiKey(ProviderType.OpenAi, "sk-test")
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            secretStore.clear()
            server.shutdown()
        }

    @Test
    fun `openai preset defaults to the official endpoint and gpt-6-luna`() {
        assertEquals(OPENAI_DEFAULT_BASE_URL, ProviderType.OpenAi.defaultBaseUrl)
        assertEquals("gpt-6-luna", OPENAI_DEFAULT_MODEL_ID)
        assertEquals(OPENAI_DEFAULT_MODEL_ID, ProviderType.OpenAi.defaultModelId)
        assertTrue(ProviderType.OpenAi in ProviderType.configurableProviders)
        assertEquals(ProviderType.OpenAi, ProviderType.fromStorage(ProviderType.OpenAi.storageValue))
    }

    @Test
    fun `only the official openai host routes to the responses api`() {
        assertTrue("https://api.openai.com/v1/chat/completions".toHttpUrl().isOfficialOpenAiHost())
        assertFalse("https://api.deepseek.com/v1/chat/completions".toHttpUrl().isOfficialOpenAiHost())
        assertFalse("https://open.bigmodel.cn/api/paas/v4/chat/completions".toHttpUrl().isOfficialOpenAiHost())
        assertFalse("http://127.0.0.1:8080/v1/chat/completions".toHttpUrl().isOfficialOpenAiHost())
    }

    @Test
    fun `non-stream request uses responses shape and replays tool results without item ids`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {"id":"resp-1","model":"gpt-6-luna","status":"completed",
                         "output":[{"type":"message","id":"msg-1","role":"assistant",
                           "content":[{"type":"output_text","text":"Battery is 80%."}]}],
                         "usage":{"input_tokens":20,"output_tokens":5,"total_tokens":25}}
                        """.trimIndent(),
                    ),
            )

            val response =
                buildProvider().generate(
                    buildRequest(
                        messageHistory =
                            listOf(
                                ModelMessage(ModelMessageRole.User, "battery?"),
                                ModelMessage(
                                    role = ModelMessageRole.Assistant,
                                    content = "",
                                    toolCalls =
                                        listOf(
                                            ProviderToolCall(
                                                id = "call-1|fc-1",
                                                name = "device.status",
                                                argumentsJson = buildJsonObject { put("scope", "battery") },
                                            ),
                                        ),
                                ),
                                ModelMessage(
                                    role = ModelMessageRole.Tool,
                                    content = "{\"battery\":80}",
                                    toolCallId = "call-1|fc-1",
                                    toolName = "device.status",
                                ),
                            ),
                        toolDescriptors = listOf(deviceStatusDescriptor()),
                    ),
                )

            val recorded = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Expected request.")
            val payload = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals("/v1/responses", recorded.path)
            assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
            assertEquals("gpt-6-luna", payload.string("model"))
            assertFalse(payload.getValue("stream").jsonPrimitive.boolean)
            assertFalse(payload.getValue("store").jsonPrimitive.boolean)
            assertEquals("system prompt", payload.string("instructions"))
            assertEquals("auto", payload.string("tool_choice"))
            val tool =
                payload
                    .getValue("tools")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("function", tool.string("type"))
            assertEquals("device_status", tool.string("name"))
            assertTrue("parameters" in tool)

            val input = payload.getValue("input").jsonArray.map { it.jsonObject }
            assertEquals(listOf("user", null, null), input.map { it.optString("role") })
            val functionCall = input[1]
            assertEquals("function_call", functionCall.string("type"))
            assertEquals("call-1", functionCall.string("call_id"))
            assertEquals("device_status", functionCall.string("name"))
            assertNull(functionCall["id"])
            val output = input[2]
            assertEquals("function_call_output", output.string("type"))
            assertEquals("call-1", output.string("call_id"))
            assertEquals("{\"battery\":80}", output.string("output"))

            assertEquals("Battery is 80%.", response.text)
            assertEquals("resp-1", response.providerRequestId)
            assertEquals(25, response.usage?.totalTokens)
        }

    @Test
    fun `request without tools omits tools and tool_choice`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"id":"resp-2","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"hi"}]}]}""",
                    ),
            )

            val response = buildProvider().generate(buildRequest())

            val payload = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertFalse("tools" in payload)
            assertFalse("tool_choice" in payload)
            assertEquals("hi", response.text)
        }

    @Test
    fun `non-stream function call maps back to the android tool name`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {"id":"resp-3","status":"completed","output":[
                          {"type":"function_call","id":"fc-9","call_id":"call-9","name":"device_status","arguments":"{\"scope\":\"battery\"}"}]}
                        """.trimIndent(),
                    ),
            )

            val response = buildProvider().generate(buildRequest(toolDescriptors = listOf(deviceStatusDescriptor())))

            val call = response.toolCalls.single()
            assertEquals("device.status", call.name)
            assertEquals("battery", call.argumentsJson.string("scope"))
            assertEquals("call-9", ResponsesToolCallId.parse(call.id).callId)
            assertEquals("tool_use", response.finishReason)
        }

    @Test
    fun `stream parses text and function call events`() =
        runTest {
            server.enqueue(
                eventStream(
                    """{"type":"response.created","response":{"id":"resp-s","model":"gpt-6-luna"}}""",
                    """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc-1","call_id":"call-1","name":"device_status","arguments":""}}""",
                    """{"type":"response.function_call_arguments.delta","delta":"{\"scope\":\"battery\"}"}""",
                    """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc-1","call_id":"call-1","name":"device_status","arguments":"{\"scope\":\"battery\"}"}}""",
                    """{"type":"response.completed","response":{"id":"resp-s","model":"gpt-6-luna","status":"completed"}}""",
                ),
            )
            server.enqueue(
                eventStream(
                    """{"type":"response.created","response":{"id":"resp-t","model":"gpt-6-luna"}}""",
                    """{"type":"response.output_text.delta","delta":"Hello "}""",
                    """{"type":"response.output_text.delta","delta":"there"}""",
                    """{"type":"response.completed","response":{"id":"resp-t","model":"gpt-6-luna","status":"completed"}}""",
                ),
            )
            val provider = buildProvider()

            val toolEvents = provider.streamGenerate(buildRequest(toolDescriptors = listOf(deviceStatusDescriptor()))).toList()
            val toolResponse = (toolEvents.last() as ModelStreamEvent.Completed).response
            val textEvents = provider.streamGenerate(buildRequest()).toList()
            val textResponse = (textEvents.last() as ModelStreamEvent.Completed).response

            val recorded = server.takeRequest()
            val payload = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals("/v1/responses", recorded.path)
            assertEquals("text/event-stream", recorded.getHeader("Accept"))
            assertTrue(payload.getValue("stream").jsonPrimitive.boolean)
            assertEquals("device.status", toolResponse.toolCalls.single().name)
            assertEquals(
                "battery",
                toolResponse.toolCalls
                    .single()
                    .argumentsJson
                    .string("scope"),
            )
            assertEquals("Hello there", textResponse.text)
            assertEquals(
                listOf("Hello ", "there"),
                textEvents.filterIsInstance<ModelStreamEvent.TextDelta>().map { it.text },
            )
        }

    @Test
    fun `stream http 400 does not fall back to chat completions`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"message":"stream unsupported for this model"}}"""),
            )

            val error =
                try {
                    buildProvider().streamGenerate(buildRequest()).toList()
                    error("Expected failure.")
                } catch (failure: ModelProviderException) {
                    failure
                }

            assertEquals(ModelProviderFailureKind.Server, error.kind)
            assertEquals(1, server.requestCount)
            assertEquals("/v1/responses", server.takeRequest().path)
        }

    @Test
    fun `chat completions 400 asking for the responses api gets a clear message`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"error":{"message":"Function tools with reasoning_effort are not supported for gpt-6-luna in /v1/chat/completions. Please use /v1/responses instead."}}""",
                    ),
            )

            val error =
                try {
                    buildProvider(useResponses = false).generate(buildRequest(toolDescriptors = listOf(deviceStatusDescriptor())))
                    error("Expected failure.")
                } catch (failure: ModelProviderException) {
                    failure
                }

            assertEquals("/v1/chat/completions", server.takeRequest().path)
            assertTrue(error.userMessage, error.userMessage.contains("Responses API"))
            assertTrue(error.userMessage, error.userMessage.contains("https://api.openai.com/v1"))
        }

    private fun buildProvider(useResponses: Boolean = true): OpenAiCompatibleProvider =
        OpenAiCompatibleProvider(
            providerType = ProviderType.OpenAi,
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
            baseHttpClient = createProviderBaseHttpClient(),
            json = json,
            useResponsesApi = { useResponses },
        )

    private fun buildRequest(
        messageHistory: List<ModelMessage> = listOf(ModelMessage(ModelMessageRole.User, "hello")),
        toolDescriptors: List<ToolDescriptor> = emptyList(),
    ): ModelRequest =
        ModelRequest(
            sessionId = "session-1",
            requestId = "req-1",
            messageHistory = messageHistory,
            systemPrompt = "system prompt",
            enabledSkills = emptyList(),
            toolDescriptors = toolDescriptors,
            runMode = ModelRunMode.Interactive,
        )

    private fun deviceStatusDescriptor(): ToolDescriptor =
        ToolDescriptor(
            name = "device.status",
            description = "Read battery and connectivity.",
        )

    private fun eventStream(vararg events: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" })

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.optString(name: String): String? = this[name]?.jsonPrimitive?.content
}
