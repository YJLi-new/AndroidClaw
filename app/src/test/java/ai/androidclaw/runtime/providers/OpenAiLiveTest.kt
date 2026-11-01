package ai.androidclaw.runtime.providers

import ai.androidclaw.data.OPENAI_DEFAULT_BASE_URL
import ai.androidclaw.data.OPENAI_DEFAULT_MODEL_ID
import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.WorkspaceStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.orchestrator.AgentTurnEvent
import ai.androidclaw.runtime.orchestrator.AgentTurnRequest
import ai.androidclaw.runtime.orchestrator.PromptAssembler
import ai.androidclaw.runtime.orchestrator.SessionLaneCoordinator
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.skills.createTestSkillManager
import ai.androidclaw.runtime.tools.ToolArgumentSpec
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.runtime.tools.createBuiltInToolRegistry
import ai.androidclaw.testutil.InMemoryProviderSecretStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.time.Clock

/**
 * Live checks against the official OpenAI API (Responses transport). Skipped unless
 * ANDROIDCLAW_LIVE_OPENAI_KEY is set; ANDROIDCLAW_LIVE_OPENAI_MODEL overrides the model.
 * Never prints the key or request headers; each test prints one LIVE| line with its latency.
 */
@RunWith(AndroidJUnit4::class)
class OpenAiLiveTest {
    private val application: android.app.Application get() = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private val apiKey: String? = System.getenv(KEY_ENV)?.trim()?.takeIf { it.isNotEmpty() }
    private val modelId: String =
        System.getenv(MODEL_ENV)?.trim()?.takeIf { it.isNotEmpty() } ?: OPENAI_DEFAULT_MODEL_ID

    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var secretStore: InMemoryProviderSecretStore
    private var database: AndroidClawDatabase? = null
    private var workspaceDir: File? = null

    @Before
    fun setUp() =
        runBlocking {
            assumeTrue("$KEY_ENV not set; live OpenAI tests skipped.", apiKey != null)
            settingsDataStore = SettingsDataStore(application)
            secretStore = InMemoryProviderSecretStore()
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAi,
                        ProviderEndpointSettings(
                            baseUrl = OPENAI_DEFAULT_BASE_URL,
                            modelId = modelId,
                            timeoutSeconds = 60,
                        ),
                    ).copy(providerType = ProviderType.OpenAi),
            )
            secretStore.writeApiKey(ProviderType.OpenAi, apiKey!!)
        }

    @After
    fun tearDown() =
        runBlocking {
            if (apiKey == null) return@runBlocking
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            secretStore.clear()
            database?.close()
            workspaceDir?.deleteRecursively()
        }

    @Test
    fun plainReplyNonStream() =
        live("plain_reply", stream = false) {
            val response = provider().generate(request("Reply with exactly the word PONG and nothing else."))
            assertTrue(response.text, response.text.contains("PONG"))
            "200, text"
        }

    @Test
    fun plainReplyStream() =
        live("plain_reply", stream = true) {
            val events = provider().streamGenerate(request("Reply with exactly the word PONG and nothing else.")).toList()
            val completed = events.last() as ModelStreamEvent.Completed
            assertTrue(events.any { it is ModelStreamEvent.TextDelta })
            assertTrue(completed.response.text, completed.response.text.contains("PONG"))
            "200, text deltas"
        }

    @Test
    fun toolCallRoundTripNonStream() =
        live("tool_round_trip", stream = false) {
            val provider = provider()
            val tool =
                ToolDescriptor(
                    name = "lookup.code",
                    description = "Look up the secret code for a named item.",
                    arguments = listOf(ToolArgumentSpec(name = "item", required = true, description = "Item name")),
                )
            val question =
                ModelMessage(ModelMessageRole.User, "Call the lookup_code tool for item 'gate', then tell me the code it returns.")
            val first = provider.generate(request(history = listOf(question), tools = listOf(tool)))
            val call = first.toolCalls.single()
            assertEquals("lookup.code", call.name)
            assertEquals(
                "gate",
                call.argumentsJson["item"]
                    ?.jsonPrimitive
                    ?.content
                    ?.lowercase(),
            )
            val second =
                provider.generate(
                    request(
                        history =
                            listOf(
                                question,
                                ModelMessage(ModelMessageRole.Assistant, first.text, toolCalls = first.toolCalls),
                                ModelMessage(
                                    role = ModelMessageRole.Tool,
                                    content = """{"item":"gate","code":"ZEBRA-4417"}""",
                                    toolCallId = call.id,
                                    toolName = call.name,
                                ),
                            ),
                        tools = listOf(tool),
                    ),
                )
            assertTrue(second.text, second.text.contains("ZEBRA-4417"))
            "200, function_call -> function_call_output -> text"
        }

    @Test
    fun agentRunnerTurnWithRealToolRegistryStream() =
        live("agent_turn_workspace_read", stream = true) {
            val db = buildTestDatabase(application).also { database = it }
            val dir = Files.createTempDirectory("androidclaw-live-ws").toFile().also { workspaceDir = it }
            val workspaceStore = WorkspaceStore(dir)
            val token = "AC-LIVE-" + (100000..999999).random()
            workspaceStore.write("notes/live-check.md", "The verification code is $token.\n")
            val sessionRepository = SessionRepository(db.sessionDao())
            val messageRepository = MessageRepository(db.messageDao())
            val taskRepository = TaskRepository(db.taskDao(), db.taskRunDao())
            val eventLogRepository = EventLogRepository(db.eventLogDao())
            val tools =
                createBuiltInToolRegistry(
                    application = application,
                    settingsDataStore = settingsDataStore,
                    sessionRepository = sessionRepository,
                    taskRepository = taskRepository,
                    schedulerCoordinator =
                        SchedulerCoordinator(
                            application = application,
                            clock = Clock.systemUTC(),
                            taskRepository = taskRepository,
                            eventLogRepository = eventLogRepository,
                        ),
                    bundledSkillsProvider = { emptyList() },
                    messageRepository = messageRepository,
                    eventLogRepository = eventLogRepository,
                    workspaceStore = workspaceStore,
                )
            val runner =
                AgentRunner(
                    providerRegistry =
                        ProviderRegistry(
                            listOf(
                                ProviderRegistry.RegisteredProviderEntry(ProviderType.Fake, "Fake", FakeProvider(Clock.systemUTC())),
                                ProviderRegistry.RegisteredProviderEntry(ProviderType.OpenAi, "OpenAI", provider()),
                            ),
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager =
                        createTestSkillManager(
                            application = application,
                            skillRepository = SkillRepository(db.skillRecordDao()),
                            toolDescriptor = tools::findDescriptor,
                        ),
                    toolRegistry = tools,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )
            val sessionId = sessionRepository.createSession("Live check").id

            val events =
                runner
                    .runInteractiveTurnStream(
                        AgentTurnRequest(
                            sessionId,
                            "Use the workspace_read tool to read notes/live-check.md, then reply with the verification code only.",
                        ),
                    ).toList()

            val failure = events.filterIsInstance<AgentTurnEvent.TurnFailed>().firstOrNull()
            assertTrue(failure?.message.orEmpty(), failure == null)
            val result = events.filterIsInstance<AgentTurnEvent.TurnCompleted>().single().result
            assertTrue(events.any { it is AgentTurnEvent.ToolFinished && it.name == "workspace_read" && it.success })
            assertTrue(result.assistantMessage, result.assistantMessage.contains(token))
            val stored = messageRepository.getMessages(sessionId)
            assertTrue(stored.any { it.role == MessageRole.ToolResult && it.content.contains(token) })
            "200, workspace_read -> answer with token"
        }

    private fun live(
        name: String,
        stream: Boolean,
        block: suspend () -> String,
    ) = runBlocking {
        val started = System.nanoTime()
        var outcome = "FAILED"
        try {
            outcome = withTimeout(LIVE_TEST_TIMEOUT_MS) { block() }
        } finally {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            println("LIVE|$name|$modelId|stream=$stream|$outcome|${elapsedMs}ms")
        }
    }

    private fun provider(): OpenAiCompatibleProvider =
        OpenAiCompatibleProvider(
            providerType = ProviderType.OpenAi,
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
            baseHttpClient = createProviderBaseHttpClient(),
            json = json,
        )

    private fun request(
        prompt: String,
    ): ModelRequest = request(history = listOf(ModelMessage(ModelMessageRole.User, prompt)), tools = emptyList())

    private fun request(
        history: List<ModelMessage>,
        tools: List<ToolDescriptor>,
    ): ModelRequest =
        ModelRequest(
            sessionId = "live-session",
            requestId = null,
            messageHistory = history,
            systemPrompt = "You are a concise test assistant.",
            enabledSkills = emptyList(),
            toolDescriptors = tools,
            runMode = ModelRunMode.Interactive,
        )

    private companion object {
        const val KEY_ENV = "ANDROIDCLAW_LIVE_OPENAI_KEY"
        const val MODEL_ENV = "ANDROIDCLAW_LIVE_OPENAI_MODEL"
        const val LIVE_TEST_TIMEOUT_MS = 90_000L
    }
}
