package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.runtime.tools.ToolArgumentSpec
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
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
 * Hosted web search request flags and response parsing. Claude is contract-tested with
 * MockWebServer only (no Claude key on this host); OpenAI uses the probe's response shapes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HostedWebSearchContractTest {
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
            val baseUrl = server.url("/v1/").toString().removeSuffix("/")
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(ProviderType.Anthropic, ProviderEndpointSettings(baseUrl, "claude-test", 5))
                    .withEndpointSettings(ProviderType.OpenAi, ProviderEndpointSettings(baseUrl, "gpt-6-luna", 5)),
            )
            secretStore.writeApiKey(ProviderType.Anthropic, "anth-test")
            secretStore.writeApiKey(ProviderType.OpenAi, "sk-test")
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            secretStore.clear()
            server.shutdown()
        }

    // ---- Claude ----

    @Test
    fun `claude request adds the web_search server tool with max_uses 5 next to function tools`() =
        runTest {
            server.enqueue(jsonResponse(CLAUDE_BATCH_WITH_SEARCH))

            anthropic().generate(request(hostedWebSearch = true))

            val body = recordedBody()
            val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("device_status", "web_search"), tools.map { it["name"]!!.jsonPrimitive.content })
            val serverTool = tools.last()
            assertEquals("web_search_20250305", serverTool["type"]!!.jsonPrimitive.content)
            assertEquals(5, serverTool["max_uses"]!!.jsonPrimitive.int)
            assertNull(serverTool["input_schema"])
            assertNull(serverTool["description"])
            assertNull(tools.first()["type"])
            assertTrue(tools.first().containsKey("input_schema"))
        }

    @Test
    fun `claude request has no server tool when hosted search is off`() =
        runTest {
            server.enqueue(jsonResponse("""{"id":"m","model":"claude-test","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""))

            val response = anthropic().generate(request(hostedWebSearch = false, tools = emptyList()))

            assertNull(recordedBody()["tools"])
            assertTrue(response.webSources.isEmpty() && response.webSearchQueries.isEmpty())
        }

    @Test
    fun `claude batch response parses query, citations first, then search results`() =
        runTest {
            server.enqueue(jsonResponse(CLAUDE_BATCH_WITH_SEARCH))

            val response = anthropic().generate(request(hostedWebSearch = true))

            assertEquals("AGP 9.5.0 is the latest stable release.", response.text)
            assertEquals("end_turn", response.finishReason)
            assertTrue("server tool use is not a function call", response.toolCalls.isEmpty())
            assertEquals(listOf("latest android gradle plugin"), response.webSearchQueries)
            assertEquals(
                listOf(
                    "https://developer.android.com/agp-9-5",
                    "https://example.com/news",
                    "https://example.com/other",
                ),
                response.webSources.map { it.url },
            )
            assertEquals("AGP 9.5.0 release notes", response.webSources.first().title)
        }

    @Test
    fun `claude stream keeps server tool input out of tool calls and collects sources`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(CLAUDE_STREAM_WITH_SEARCH),
            )

            val events = anthropic().streamGenerate(request(hostedWebSearch = true)).toList()

            val response = (events.last() as ModelStreamEvent.Completed).response
            assertTrue(events.none { it is ModelStreamEvent.ToolCallDelta })
            assertTrue(response.toolCalls.isEmpty())
            assertEquals("AGP 9.5.0 is current.", response.text)
            assertEquals(listOf("agp latest"), response.webSearchQueries)
            assertEquals(listOf("https://developer.android.com/agp-9-5", "https://example.com/r2"), response.webSources.map { it.url })
        }

    @Test
    fun `claude history replay sends only text for assistant turns`() =
        runTest {
            server.enqueue(jsonResponse("""{"id":"m","model":"claude-test","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""))

            anthropic().generate(
                request(
                    hostedWebSearch = true,
                    history =
                        listOf(
                            ModelMessage(ModelMessageRole.User, "latest agp?"),
                            ModelMessage(ModelMessageRole.Assistant, "AGP 9.5.0 ([developer.android.com](https://developer.android.com/agp-9-5))"),
                            ModelMessage(ModelMessageRole.User, "thanks"),
                        ),
                ),
            )

            val assistant = recordedBody()["messages"]!!.jsonArray[1].jsonObject
            val blocks = assistant["content"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("text"), blocks.map { it["type"]!!.jsonPrimitive.content })
        }

    // ---- OpenAI Responses ----

    @Test
    fun `openai request adds the web_search tool after function tools`() =
        runTest {
            server.enqueue(jsonResponse(fixture("openai_responses_web_search.json")))

            val response = openAi().generate(request(hostedWebSearch = true))

            val tools = recordedBody()["tools"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("function", "web_search"), tools.map { it["type"]!!.jsonPrimitive.content })
            assertEquals(1, tools.last().size)
            assertEquals(1, response.webSources.size)
            assertEquals(1, response.webSearchQueries.size)
        }

    @Test
    fun `openai hosted search without function tools still sends a tools array`() =
        runTest {
            server.enqueue(jsonResponse(fixture("openai_responses_web_search.json")))

            openAi().generate(request(hostedWebSearch = true, tools = emptyList()))

            val body = recordedBody()
            assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
            assertEquals(listOf("web_search"), body["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        }

    @Test
    fun `openai request has no hosted tool when off and replays assistant text only`() =
        runTest {
            server.enqueue(jsonResponse(fixture("openai_responses_web_search.json")))

            openAi().generate(
                request(
                    hostedWebSearch = false,
                    history =
                        listOf(
                            ModelMessage(ModelMessageRole.User, "latest agp?"),
                            ModelMessage(ModelMessageRole.Assistant, "AGP 9.5.0"),
                            ModelMessage(ModelMessageRole.User, "thanks"),
                        ),
                ),
            )

            val body = recordedBody()
            assertFalse(body["tools"]!!.jsonArray.any { it.jsonObject["type"]!!.jsonPrimitive.content == "web_search" })
            val assistant = body["input"]!!.jsonArray[1].jsonObject
            assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
            assertTrue(assistant["content"] is JsonPrimitive)
            assertEquals(2, assistant.size)
        }

    @Test
    fun `openai stream with hosted search completes with sources`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(fixture("openai_responses_web_search_stream.txt")),
            )

            val events = openAi().streamGenerate(request(hostedWebSearch = true)).toList()

            val response = (events.last() as ModelStreamEvent.Completed).response
            assertTrue(events.any { it is ModelStreamEvent.TextDelta })
            assertEquals(2, response.webSearchQueries.size)
            assertEquals(1, response.webSources.size)
        }

    private fun anthropic(): AnthropicProvider =
        AnthropicProvider(
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
            baseHttpClient = OkHttpClient(),
            json = json,
        )

    private fun openAi(): OpenAiCompatibleProvider =
        OpenAiCompatibleProvider(
            providerType = ProviderType.OpenAi,
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
            baseHttpClient = OkHttpClient(),
            json = json,
            useResponsesApi = { true },
        )

    private fun request(
        hostedWebSearch: Boolean,
        tools: List<ToolDescriptor> =
            listOf(
                ToolDescriptor(
                    name = "device_status",
                    description = "Device status.",
                    arguments = listOf(ToolArgumentSpec(name = "scope", description = "Scope")),
                ),
            ),
        history: List<ModelMessage> = listOf(ModelMessage(ModelMessageRole.User, "What is the latest stable AGP?")),
    ): ModelRequest =
        ModelRequest(
            sessionId = "s1",
            requestId = "r1",
            messageHistory = history,
            systemPrompt = "You are AndroidClaw.",
            enabledSkills = emptyList(),
            toolDescriptors = tools,
            runMode = ModelRunMode.Interactive,
            hostedWebSearch = hostedWebSearch,
        )

    private fun jsonResponse(body: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun recordedBody(): JsonObject =
        json
            .parseToJsonElement(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
            .jsonObject

    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader?.getResource("fixtures/websearch/$name")) { "missing fixture $name" }.readText()

    private companion object {
        val CLAUDE_BATCH_WITH_SEARCH =
            """
            {
              "id": "msg_1",
              "model": "claude-test",
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 100, "output_tokens": 20},
              "content": [
                {"type": "server_tool_use", "id": "srvtoolu_1", "name": "web_search", "input": {"query": "latest android gradle plugin"}},
                {"type": "web_search_tool_result", "tool_use_id": "srvtoolu_1", "content": [
                  {"type": "web_search_result", "url": "https://example.com/news", "title": "News", "encrypted_content": "x", "page_age": "1 day"},
                  {"type": "web_search_result", "url": "https://developer.android.com/agp-9-5#notes", "title": "Dup of cited", "encrypted_content": "y"},
                  {"type": "web_search_result", "url": "https://example.com/other?utm_source=x", "title": "Other", "encrypted_content": "z"}
                ]},
                {"type": "text", "text": "AGP 9.5.0 is the latest stable release.", "citations": [
                  {"type": "web_search_result_location", "url": "https://developer.android.com/agp-9-5", "title": "AGP 9.5.0 release notes", "encrypted_index": "e", "cited_text": "9.5.0"}
                ]}
              ]
            }
            """.trimIndent()

        val CLAUDE_STREAM_WITH_SEARCH =
            """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_s","model":"claude-test","usage":{"input_tokens":10,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_s","name":"web_search","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\": \"agp"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":" latest\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_s","content":[{"type":"web_search_result","url":"https://example.com/r2","title":"R2","encrypted_content":"x"}]}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: content_block_start
            data: {"type":"content_block_start","index":2,"content_block":{"type":"text","text":"","citations":[]}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://developer.android.com/agp-9-5","title":"AGP","encrypted_index":"e","cited_text":"9.5.0"}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"AGP 9.5.0 is current."}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":2}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}

            event: message_stop
            data: {"type":"message_stop"}

            """.trimIndent()
    }
}
