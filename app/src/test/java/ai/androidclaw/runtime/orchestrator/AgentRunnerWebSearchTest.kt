package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.InMemoryWebSearchKeyStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.providers.ProviderToolCall
import ai.androidclaw.runtime.providers.WebSource
import ai.androidclaw.runtime.providers.parseProviderMessageMeta
import ai.androidclaw.runtime.skills.createTestSkillManager
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolInvocationOrigin
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.runtime.tools.WEB_SEARCH_TOOL_NAME
import ai.androidclaw.runtime.tools.WebSearchPolicy
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The search rule as AgentRunner applies it: tool exposure, the hosted flag and persisted sources. */
@RunWith(AndroidJUnit4::class)
class AgentRunnerWebSearchTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var messageRepository: MessageRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var sessionId: String
    private val keyStore = InMemoryWebSearchKeyStore()
    private val requests = mutableListOf<ModelRequest>()

    @Before
    fun setUp() =
        runTest {
            application = ApplicationProvider.getApplicationContext()
            database = buildTestDatabase(application)
            messageRepository = MessageRepository(database.messageDao())
            sessionRepository = SessionRepository(database.sessionDao())
            settingsDataStore = SettingsDataStore(application)
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot().copy(providerType = ProviderType.Anthropic))
            settingsDataStore.setWebSearchEnabled(true)
            sessionId = sessionRepository.createSession("Search").id
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            settingsDataStore.setWebSearchEnabled(true)
            database.close()
        }

    @Test
    fun `hosted mode hides the typed tool, sets the flag and stores sources`() =
        runTest {
            val runner =
                runner(
                    ModelResponse(
                        text = "AGP 9.5.0.",
                        webSearchQueries = listOf("agp latest"),
                        webSources = listOf(WebSource("https://developer.android.com/agp", "AGP")),
                    ),
                )

            val result = runner.runInteractiveTurn(AgentTurnRequest(sessionId, "latest agp?"))

            val request = requests.single()
            assertTrue(request.hostedWebSearch)
            assertFalse(request.toolDescriptors.any { it.name == WEB_SEARCH_TOOL_NAME })
            assertTrue(request.toolDescriptors.any { it.name == "device_status" })
            val meta = requireNotNull(parseProviderMessageMeta(result.providerMeta))
            assertEquals(listOf("agp latest"), meta.webSearchQueries)
            assertEquals(listOf(WebSource("https://developer.android.com/agp", "AGP")), meta.sources)
            val stored = messageRepository.getMessages(sessionId).last { it.role == MessageRole.Assistant }
            assertEquals("AGP 9.5.0.", stored.content)
        }

    @Test
    fun `brave key exposes the typed tool without the hosted flag and its results become sources`() =
        runTest {
            keyStore.writeBraveApiKey("brave")
            val runner =
                runner(
                    ModelResponse(
                        text = "",
                        finishReason = "tool_use",
                        toolCalls =
                            listOf(
                                ProviderToolCall(
                                    id = "call_1",
                                    name = WEB_SEARCH_TOOL_NAME,
                                    argumentsJson = buildJsonObject { put("query", "agp latest") },
                                ),
                            ),
                    ),
                    ModelResponse(text = "AGP 9.5.0 per the results."),
                )

            val result = runner.runInteractiveTurn(AgentTurnRequest(sessionId, "latest agp?"))

            assertEquals(2, requests.size)
            requests.forEach { request ->
                assertFalse(request.hostedWebSearch)
                assertTrue(request.toolDescriptors.any { it.name == WEB_SEARCH_TOOL_NAME })
            }
            val meta = requireNotNull(parseProviderMessageMeta(result.providerMeta))
            assertEquals(listOf("agp latest"), meta.webSearchQueries)
            assertEquals(listOf("https://example.com/agp"), meta.sources.map { it.url })
        }

    @Test
    fun `off in settings hides the tool and never sets the flag`() =
        runTest {
            keyStore.writeBraveApiKey("brave")
            settingsDataStore.setWebSearchEnabled(false)
            val runner = runner(ModelResponse(text = "No search."))

            val result = runner.runInteractiveTurn(AgentTurnRequest(sessionId, "latest agp?"))

            val request = requests.single()
            assertFalse(request.hostedWebSearch)
            assertFalse(request.toolDescriptors.any { it.name == WEB_SEARCH_TOOL_NAME })
            assertTrue(requireNotNull(parseProviderMessageMeta(result.providerMeta)).sources.isEmpty())
        }

    @Test
    fun `scheduled runs get the typed tool when a brave key exists`() =
        runTest {
            keyStore.writeBraveApiKey("brave")
            val runner = runner(ModelResponse(text = "Done."))

            runner.runScheduledTurn(sessionId = sessionId, userMessage = "daily news")

            assertTrue(requests.single().toolDescriptors.any { it.name == WEB_SEARCH_TOOL_NAME })
        }

    private fun runner(vararg responses: ModelResponse): AgentRunner {
        val queue = ArrayDeque(responses.toList())
        val provider =
            object : ModelProvider {
                override val id: String = ProviderType.Anthropic.providerId

                override suspend fun generate(request: ModelRequest): ModelResponse {
                    requests += request
                    return queue.removeFirst()
                }
            }
        val toolRegistry =
            ToolRegistry(
                tools =
                    listOf(
                        stubTool("device_status") { ToolExecutionResult.success("ok", buildJsonObject {}) },
                        stubTool(WEB_SEARCH_TOOL_NAME) {
                            ToolExecutionResult.success(
                                summary = "Found 1 web results.",
                                payload =
                                    buildJsonObject {
                                        put("query", "agp latest")
                                        put(
                                            "results",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("title", "AGP")
                                                        put("url", "https://example.com/agp")
                                                        put("snippet", "9.5.0")
                                                    },
                                                )
                                            },
                                        )
                                    },
                            )
                        },
                    ),
            )
        return AgentRunner(
            providerRegistry = buildTestProviderRegistry(anthropicProvider = provider),
            settingsDataStore = settingsDataStore,
            messageRepository = messageRepository,
            skillManager =
                createTestSkillManager(
                    application = application,
                    skillRepository = SkillRepository(database.skillRecordDao()),
                    toolDescriptor = toolRegistry::findDescriptor,
                ),
            toolRegistry = toolRegistry,
            sessionLaneCoordinator = SessionLaneCoordinator(),
            promptAssembler = PromptAssembler(),
            webSearchPolicy = WebSearchPolicy(settingsDataStore, keyStore),
        )
    }

    private fun stubTool(
        name: String,
        result: () -> ToolExecutionResult,
    ): ToolRegistry.Entry =
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = name,
                    description = "Stub $name",
                    allowedOrigins = setOf(ToolInvocationOrigin.Model, ToolInvocationOrigin.ScheduledModel),
                ),
        ) { _, _ -> result() }
}
