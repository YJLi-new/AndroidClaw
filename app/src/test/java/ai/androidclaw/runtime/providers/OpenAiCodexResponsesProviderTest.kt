package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderOAuthCredential
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OpenAiCodexResponsesProviderTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var secretStore: InMemoryProviderSecretStore
    private lateinit var server: MockWebServer
    private lateinit var oAuthClient: RecordingOAuthClient
    private val json = Json { ignoreUnknownKeys = true }
    private val clock =
        Clock.fixed(
            Instant.parse("2026-04-27T00:00:00Z"),
            ZoneOffset.UTC,
        )

    @Before
    fun setUp() =
        runTest {
            settingsDataStore =
                SettingsDataStore(
                    ApplicationProvider.getApplicationContext(),
                )
            secretStore = InMemoryProviderSecretStore()
            server = MockWebServer()
            server.start()
            oAuthClient = RecordingOAuthClient()
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAiCodex,
                        ProviderEndpointSettings(
                            baseUrl = server.url("/backend-api/codex/").toString().removeSuffix("/"),
                            modelId = "gpt-5.5",
                            timeoutSeconds = 1,
                        ),
                    ).copy(providerType = ProviderType.OpenAiCodex),
            )
            secretStore.writeOAuthCredential(
                ProviderType.OpenAiCodex,
                ProviderOAuthCredential(
                    provider = ProviderType.OpenAiCodex.providerId,
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    expiresAtEpochMillis = clock.millis() + 600_000,
                    email = "codex@example.test",
                    profileName = "codex@example.test",
                ),
            )
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            secretStore.clear()
            server.shutdown()
        }

    @Test
    fun `responses request streams text and usage`() =
        runTest {
            server.enqueue(eventStreamResponse(textResponseEvents()))

            val response = buildProvider().generate(buildRequest())
            val recordedRequest =
                server.takeRequest(5, TimeUnit.SECONDS)
                    ?: error("Expected Codex request.")
            val payload = json.parseToJsonElement(recordedRequest.body.readUtf8()).jsonObject
            val input = payload.getValue("input").jsonArray

            assertEquals("OK", response.text)
            assertEquals("resp-123", response.providerRequestId)
            assertEquals("gpt-5.5", response.modelId)
            assertEquals(7, response.usage?.inputTokens)
            assertEquals(2, response.usage?.outputTokens)
            assertEquals(9, response.usage?.totalTokens)
            assertEquals("/backend-api/codex/responses", recordedRequest.path)
            assertEquals("Bearer access-token", recordedRequest.getHeader("Authorization"))
            assertEquals("req-123", recordedRequest.getHeader("X-Request-Id"))
            assertEquals("gpt-5.5", payload.getValue("model").jsonPrimitive.content)
            assertEquals("system prompt", payload.getValue("instructions").jsonPrimitive.content)
            assertEquals("user", input[0].jsonObject.getValue("role").jsonPrimitive.content)
        }

    @Test
    fun `responses request maps dotted tools and parses function calls back to android names`() =
        runTest {
            server.enqueue(eventStreamResponse(toolCallEvents()))

            val response =
                buildProvider().generate(
                    buildRequest(
                        toolDescriptors =
                            listOf(
                                ToolDescriptor(
                                    name = "health.status",
                                    description = "Report health",
                                    inputSchema =
                                        buildJsonObject {
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
            val recordedRequest =
                server.takeRequest(5, TimeUnit.SECONDS)
                    ?: error("Expected Codex request.")
            val payload = json.parseToJsonElement(recordedRequest.body.readUtf8()).jsonObject
            val tools = payload.getValue("tools").jsonArray
            val toolCall = response.toolCalls.single()

            assertEquals("tool_use", response.finishReason)
            assertEquals("call-1|fc-1", toolCall.id)
            assertEquals("health.status", toolCall.name)
            assertEquals("summary", toolCall.argumentsJson.getValue("scope").jsonPrimitive.content)
            assertEquals(
                "health_status",
                tools.single().jsonObject.getValue("name").jsonPrimitive.content,
            )
        }

    @Test
    fun `expired oauth credential is refreshed before provider call`() =
        runTest {
            secretStore.writeOAuthCredential(
                ProviderType.OpenAiCodex,
                ProviderOAuthCredential(
                    provider = ProviderType.OpenAiCodex.providerId,
                    accessToken = "old-access",
                    refreshToken = "old-refresh",
                    expiresAtEpochMillis = clock.millis() - 1,
                ),
            )
            server.enqueue(eventStreamResponse(textResponseEvents()))

            buildProvider().generate(buildRequest())
            val recordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

            assertEquals(1, oAuthClient.refreshCount)
            assertEquals("Bearer refreshed-access", recordedRequest.getHeader("Authorization"))
            assertEquals("refreshed-access", secretStore.readOAuthCredential(ProviderType.OpenAiCodex)?.accessToken)
        }

    @Test
    fun `streamGenerate emits text delta before completion`() =
        runTest {
            server.enqueue(eventStreamResponse(textResponseEvents()))

            val events = buildProvider().streamGenerate(buildRequest()).toList()

            assertTrue(events.any { it == ModelStreamEvent.TextDelta("OK") })
            assertTrue(events.last() is ModelStreamEvent.Completed)
        }

    private fun buildProvider(): OpenAiCodexResponsesProvider =
        OpenAiCodexResponsesProvider(
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
            oAuthClient = oAuthClient,
            baseHttpClient = createProviderBaseHttpClient(),
            json = json,
            clock = clock,
        )

    private fun buildRequest(toolDescriptors: List<ToolDescriptor> = emptyList()): ModelRequest =
        ModelRequest(
            sessionId = "session-123",
            requestId = "req-123",
            messageHistory =
                listOf(
                    ModelMessage(
                        role = ModelMessageRole.User,
                        content = "hello",
                    ),
                ),
            systemPrompt = "system prompt",
            enabledSkills = emptyList(),
            toolDescriptors = toolDescriptors,
            runMode = ModelRunMode.Interactive,
        )

    private fun eventStreamResponse(events: List<String>): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" })

    private fun textResponseEvents(): List<String> =
        listOf(
            """{"type":"response.created","response":{"id":"resp-123","model":"gpt-5.5"}}""",
            """{"type":"response.output_item.added","item":{"type":"message","id":"msg-1"}}""",
            """{"type":"response.output_text.delta","delta":"OK"}""",
            """{"type":"response.completed","response":{"id":"resp-123","model":"gpt-5.5","status":"completed","usage":{"input_tokens":7,"output_tokens":2,"total_tokens":9}}}""",
        )

    private fun toolCallEvents(): List<String> =
        listOf(
            """{"type":"response.created","response":{"id":"resp-tools","model":"gpt-5.5"}}""",
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc-1","call_id":"call-1","name":"health_status","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","delta":"{\"scope\":\"summary\"}"}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc-1","call_id":"call-1","name":"health_status","arguments":"{\"scope\":\"summary\"}"}}""",
            """{"type":"response.completed","response":{"id":"resp-tools","model":"gpt-5.5","status":"completed"}}""",
        )

    private class RecordingOAuthClient : OpenAiCodexOAuthClient {
        var refreshCount: Int = 0
            private set

        override suspend fun loginWithDeviceCode(
            onVerification: suspend (OpenAiCodexDeviceCodePrompt) -> Unit,
            onProgress: suspend (String) -> Unit,
        ): ProviderOAuthCredential = error("Not used")

        override suspend fun refreshCredential(credential: ProviderOAuthCredential): ProviderOAuthCredential {
            refreshCount += 1
            return credential.copy(
                accessToken = "refreshed-access",
                refreshToken = "refreshed-refresh",
                expiresAtEpochMillis = 1_800_000_000_000,
            )
        }
    }
}
