package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MESSAGE_CONTENT_MAX_CHARS
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.memory.MemoryCoordinator
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelProviderException
import ai.androidclaw.runtime.providers.ModelProviderFailureKind
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.providers.ModelStreamEvent
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.providers.NetworkStatusSnapshot
import ai.androidclaw.runtime.providers.OpenAiCompatibleProvider
import ai.androidclaw.runtime.providers.ProviderToolCall
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.skills.BundledSkillLoader
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibility
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillParser
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.skills.SkillSourceType
import ai.androidclaw.runtime.skills.createTestSkillManager
import ai.androidclaw.runtime.tools.ToolAvailability
import ai.androidclaw.runtime.tools.ToolAvailabilityStatus
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolPermissionRequirement
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.runtime.tools.createBuiltInToolRegistry
import ai.androidclaw.testutil.InMemoryProviderSecretStore
import ai.androidclaw.testutil.buildTestProviderRegistry
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AgentRunnerTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var messageRepository: MessageRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var eventLogRepository: EventLogRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var sessionId: String

    @Before
    fun setUp() =
        runTest {
            application = ApplicationProvider.getApplicationContext()
            database = buildTestDatabase(application)
            messageRepository = MessageRepository(database.messageDao())
            sessionRepository = SessionRepository(database.sessionDao())
            eventLogRepository = EventLogRepository(database.eventLogDao())
            taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
            memoryRepository = MemoryRepository(database.memoryItemDao())
            settingsDataStore = SettingsDataStore(application)
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            settingsDataStore.setMemoryEnabled(false)
            sessionId = sessionRepository.createSession("Test session").id
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            settingsDataStore.setMemoryEnabled(false)
            memoryRepository.clear(settingsDataStore.memorySettingsSnapshot().installUserId)
            database.close()
        }

    @Test
    fun `slash tool skill executes direct tool path without provider`() =
        runTest {
            val toolRegistry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "tasks.list",
                                        description = "List tasks",
                                    ),
                            ) { _, arguments ->
                                ToolExecutionResult.success(
                                    summary = "Tasks tool reached",
                                    payload =
                                        buildJsonObject {
                                            put("command", arguments["command"]?.jsonPrimitive?.content.orEmpty())
                                        },
                                )
                            },
                        ),
                )
            val skillManager = buildSkillManager(toolRegistry)
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider = failOnGenerateProvider(),
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = skillManager,
                    toolRegistry = toolRegistry,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            val result =
                runner.runInteractiveTurn(
                    AgentTurnRequest(
                        sessionId = sessionId,
                        userMessage = "/list_tasks pending",
                    ),
                )

            assertTrue(result.assistantMessage.contains("Tasks tool reached"))
            assertEquals(listOf("list_tasks"), result.selectedSkills.map { it.displayName })
            assertNotNull(result.directToolResult)
            assertEquals(
                "pending",
                result.directToolResult
                    ?.payload
                    ?.get("command")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertNull(result.providerRequestId)
            val storedMessages = messageRepository.getRecentMessages(sessionId, limit = 10)
            assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.ToolCall })
            assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.ToolResult })
            assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.Assistant })
        }

    @Test
    fun `compact slash command stores explicit summary without provider`() =
        runTest {
            val toolRegistry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "sessions.compact",
                                        description = "Compact session",
                                    ),
                            ) { context, arguments ->
                                val summary = arguments["command"]?.jsonPrimitive?.content.orEmpty()
                                sessionRepository.updateSummary(context.sessionId.orEmpty(), summary)
                                ToolExecutionResult.success(
                                    summary = "Compacted this session with an explicit summary.",
                                    payload =
                                        buildJsonObject {
                                            put("summaryText", summary)
                                        },
                                )
                            },
                        ),
                )
            val skillManager =
                buildSkillManager(
                    toolRegistry = toolRegistry,
                    skills =
                        listOf(
                            skillSnapshot(
                                id = "compact",
                                name = "compact",
                                commandDispatch = SkillCommandDispatch.Tool,
                                commandTool = "sessions.compact",
                            ),
                        ),
                )
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider = failOnGenerateProvider(),
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = skillManager,
                    toolRegistry = toolRegistry,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            val result =
                runner.runInteractiveTurn(
                    AgentTurnRequest(
                        sessionId = sessionId,
                        userMessage = "/compact Goal: preserve compact context. Next: validate.",
                    ),
                )

            assertTrue(result.assistantMessage.contains("Compacted this session"))
            assertEquals(listOf("compact"), result.selectedSkills.map { it.displayName })
            assertNull(result.providerRequestId)
            assertEquals(
                "Goal: preserve compact context. Next: validate.",
                sessionRepository.getSession(sessionId)?.summaryText,
            )
        }

    @Test
    fun `compact slash command without text generates local summary and stores boundary`() =
        runTest {
            messageRepository.addMessage(sessionId, ai.androidclaw.data.model.MessageRole.User, "We are improving compact behavior.")
            val boundary =
                messageRepository.addMessage(
                    sessionId,
                    ai.androidclaw.data.model.MessageRole.Assistant,
                    "Next step is to hide older turns without deleting them.",
                )
            val testClock = Clock.fixed(Instant.parse("2026-03-08T00:00:00Z"), ZoneOffset.UTC)
            val schedulerCoordinator =
                SchedulerCoordinator(
                    application = application,
                    clock = testClock,
                    taskRepository = taskRepository,
                    eventLogRepository = eventLogRepository,
                )
            val toolRegistry =
                createBuiltInToolRegistry(
                    application = application,
                    settingsDataStore = settingsDataStore,
                    sessionRepository = sessionRepository,
                    taskRepository = taskRepository,
                    schedulerCoordinator = schedulerCoordinator,
                    bundledSkillsProvider = { emptyList() },
                    messageRepository = messageRepository,
                    eventLogRepository = eventLogRepository,
                    clock = testClock,
                )
            val skillManager =
                buildSkillManager(
                    toolRegistry = toolRegistry,
                    skills =
                        listOf(
                            skillSnapshot(
                                id = "compact",
                                name = "compact",
                                commandDispatch = SkillCommandDispatch.Tool,
                                commandTool = "sessions.compact",
                            ),
                        ),
                )
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider = failOnGenerateProvider(),
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = skillManager,
                    toolRegistry = toolRegistry,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                    loadSessionSummary = { id -> sessionRepository.getSession(id)?.summaryText },
                    loadSessionCompactionBoundary = { id -> sessionRepository.getSession(id)?.compactedUntilMessageId },
                )

            val result =
                runner.runInteractiveTurn(
                    AgentTurnRequest(
                        sessionId = sessionId,
                        userMessage = "/compact",
                    ),
                )

            val storedSession = sessionRepository.getSession(sessionId)
            assertTrue(result.assistantMessage.contains("Compacted this session"))
            val storedBoundaryId = storedSession?.compactedUntilMessageId
            assertNotNull(storedBoundaryId)
            assertTrue(storedBoundaryId != boundary.id)
            val storedBoundaryMessage =
                messageRepository
                    .getMessagesByIds(listOf(storedBoundaryId.orEmpty()))
                    .values
                    .single()
            assertEquals(ai.androidclaw.data.model.MessageRole.ToolCall, storedBoundaryMessage.role)
            assertTrue(storedBoundaryMessage.content.startsWith("Tool request: sessions.compact"))
            assertTrue(!storedBoundaryMessage.content.contains("improving compact behavior"))
            assertTrue(storedSession?.summaryText.orEmpty().contains("Recent transcript:"))
            assertTrue(storedSession?.summaryText.orEmpty().contains("improving compact behavior"))
            assertTrue(storedSession?.summaryText.orEmpty().contains("hide older turns"))
        }

    @Test
    fun `blocked slash skill returns eligibility reason instead of falling through to provider`() =
        runTest {
            val toolRegistry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "tasks.list",
                                        description = "List tasks",
                                        requiredPermissions =
                                            listOf(
                                                ToolPermissionRequirement(
                                                    permission = "android.permission.POST_NOTIFICATIONS",
                                                    displayName = "Task access",
                                                ),
                                            ),
                                    ),
                                availabilityProvider = {
                                    ToolAvailability(
                                        status = ToolAvailabilityStatus.PermissionRequired,
                                        reason = "Grant task access.",
                                    )
                                },
                            ) { _, _ ->
                                ToolExecutionResult.success(
                                    summary = "should not run",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )
            val skillManager = buildSkillManager(toolRegistry)
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider = failOnGenerateProvider(),
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = skillManager,
                    toolRegistry = toolRegistry,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            val result =
                runner.runInteractiveTurn(
                    AgentTurnRequest(
                        sessionId = sessionId,
                        userMessage = "/list_tasks",
                    ),
                )

            assertTrue(result.assistantMessage.contains("Skill /list_tasks is unavailable."))
            assertTrue(result.assistantMessage.contains("Tool blocked: tasks.list"))
            assertEquals(listOf("list_tasks"), result.selectedSkills.map { it.displayName })
            assertNull(result.directToolResult)
            assertNull(result.providerRequestId)
        }

    @Test
    fun `provider-driven tool call loop persists tool messages and final assistant response`() =
        runTest {
            val toolRegistry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "health.status",
                                        description = "Report health",
                                    ),
                            ) { _, _ ->
                                ToolExecutionResult.success(
                                    summary = "Health okay",
                                    payload =
                                        buildJsonObject {
                                            put("status", "ok")
                                        },
                                )
                            },
                        ),
                )
            val runner =
                AgentRunner(
                    providerRegistry = buildTestProviderRegistry(),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(toolRegistry),
                    toolRegistry = toolRegistry,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            val result =
                runner.runInteractiveTurn(
                    AgentTurnRequest(
                        sessionId = sessionId,
                        userMessage = "Please inspect [tool:health.status]",
                    ),
                )

            val storedMessages = messageRepository.getRecentMessages(sessionId, limit = 10)

            assertTrue(result.assistantMessage.contains("Reply: Please inspect [tool:health.status]"))
            assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.ToolCall })
            assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.ToolResult })
            assertTrue(
                storedMessages.any { message ->
                    message.role == ai.androidclaw.data.model.MessageRole.Assistant &&
                        message.content.contains("Tool result:")
                },
            )
        }

    @Test
    fun `provider turn uses compacted summary and excludes hidden boundary history`() =
        runTest {
            messageRepository.addMessage(sessionId, ai.androidclaw.data.model.MessageRole.User, "old setup should be hidden")
            val boundary =
                messageRepository.addMessage(
                    sessionId,
                    ai.androidclaw.data.model.MessageRole.Assistant,
                    "old answer should be hidden",
                )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = sessionId,
                summaryText = "Older turns covered compact setup.",
                compactedUntilMessageId = boundary.id,
            )
            messageRepository.addMessage(sessionId, ai.androidclaw.data.model.MessageRole.User, "/compact")
            messageRepository.addMessage(
                sessionId,
                ai.androidclaw.data.model.MessageRole.ToolCall,
                "Tool request: sessions.compact {\"summary\":\"old setup should be hidden in compact payload\"}",
            )
            messageRepository.addMessage(
                sessionId,
                ai.androidclaw.data.model.MessageRole.ToolResult,
                "Tool result: Compacted this session summary and hid older messages.",
            )
            messageRepository.addMessage(
                sessionId,
                ai.androidclaw.data.model.MessageRole.Assistant,
                "Compacted this session summary and hid older messages.\n\nActive skills: compact",
            )
            var capturedRequest: ModelRequest? = null
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse {
                                        capturedRequest = request
                                        return ModelResponse(text = "Reply after compact")
                                    }
                                },
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(ToolRegistry(emptyList()), skills = emptyList()),
                    toolRegistry = ToolRegistry(emptyList()),
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                    loadSessionSummary = { id -> sessionRepository.getSession(id)?.summaryText },
                    loadSessionCompactionBoundary = { id -> sessionRepository.getSession(id)?.compactedUntilMessageId },
                )

            runner.runInteractiveTurn(
                AgentTurnRequest(
                    sessionId = sessionId,
                    userMessage = "continue after compact",
                ),
            )

            val historyText = capturedRequest?.messageHistory?.joinToString("\n") { it.content }.orEmpty()
            assertTrue(historyText.contains("Session summary: Older turns covered compact setup."))
            assertTrue(historyText.contains("continue after compact"))
            assertTrue(!historyText.contains("old setup should be hidden"))
            assertTrue(!historyText.contains("old answer should be hidden"))
            assertTrue(!historyText.contains("old setup should be hidden in compact payload"))
            assertTrue(!historyText.contains("Tool result: Compacted this session"))
        }

    @Test
    fun `provider turn injects relevant cross session memories`() =
        runTest {
            settingsDataStore.setMemoryEnabled(true)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            memoryRepository.remember(ownerUserId, "User prefers compact Kotlin UI.")
            var capturedRequest: ModelRequest? = null
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse {
                                        capturedRequest = request
                                        return ModelResponse(text = "Use compact Kotlin UI.")
                                    }
                                },
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(ToolRegistry(emptyList()), skills = emptyList()),
                    toolRegistry = ToolRegistry(emptyList()),
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                    memoryCoordinator = MemoryCoordinator(settingsDataStore, memoryRepository),
                )

            runner.runInteractiveTurn(
                AgentTurnRequest(
                    sessionId = sessionId,
                    userMessage = "What Kotlin UI do I prefer?",
                ),
            )

            val memoryContext =
                capturedRequest
                    ?.messageHistory
                    ?.firstOrNull()
                    ?.content
                    .orEmpty()
            assertTrue(memoryContext.contains("Relevant cross-session memories:"))
            assertTrue(memoryContext.contains("User prefers compact Kotlin UI."))
        }

    @Test
    fun `provider turn bounds tool descriptors before model request`() =
        runTest {
            val oversizedDescription = "d".repeat(MAX_PROMPT_TOOL_DESCRIPTION_CHARS) + "DESCRIPTION_TAIL"
            val oversizedAlias = "a".repeat(MAX_PROMPT_TOOL_ALIAS_CHARS) + "ALIAS_TAIL"
            val oversizedSchemaText = "s".repeat(MAX_MODEL_TOOL_INPUT_SCHEMA_STRING_CHARS) + "SCHEMA_TAIL"
            val oversizedSchema =
                buildJsonObject {
                    put("type", "object")
                    put("description", oversizedSchemaText)
                    put(
                        "enum",
                        buildJsonArray {
                            repeat(MAX_MODEL_TOOL_INPUT_SCHEMA_ENTRIES + 1) { index ->
                                add(JsonPrimitive("choice-$index"))
                            }
                        },
                    )
                }
            val toolRegistry =
                ToolRegistry(
                    tools =
                        (1..(MAX_PROMPT_TOOLS + 1)).map { index ->
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "bounded.tool.%03d".format(index),
                                        description = if (index == 1) oversizedDescription else "Tool $index",
                                        aliases = if (index == 1) listOf(oversizedAlias) else emptyList(),
                                        inputSchema = if (index == 1) oversizedSchema else buildJsonObject { put("type", "object") },
                                    ),
                            ) { _, _ ->
                                ToolExecutionResult.success(
                                    summary = "ok",
                                    payload = buildJsonObject {},
                                )
                            }
                        },
                )
            var capturedRequest: ModelRequest? = null
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse {
                                        capturedRequest = request
                                        return ModelResponse(text = "Tool descriptor bounds checked.")
                                    }
                                },
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(toolRegistry, skills = emptyList()),
                    toolRegistry = toolRegistry,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            runner.runInteractiveTurn(
                AgentTurnRequest(
                    sessionId = sessionId,
                    userMessage = "hello",
                ),
            )

            val descriptors = checkNotNull(capturedRequest).toolDescriptors
            val firstDescriptor = descriptors.first { it.name == "bounded.tool.001" }
            val firstSchema = firstDescriptor.inputSchema.toString()

            assertEquals(MAX_PROMPT_TOOLS, descriptors.size)
            assertFalse(descriptors.any { it.name == "bounded.tool.%03d".format(MAX_PROMPT_TOOLS + 1) })
            assertEquals(MAX_PROMPT_TOOL_DESCRIPTION_CHARS, firstDescriptor.description.length)
            assertFalse(firstDescriptor.description.contains("DESCRIPTION_TAIL"))
            assertEquals(MAX_PROMPT_TOOL_ALIAS_CHARS, firstDescriptor.aliases.single().length)
            assertFalse(firstDescriptor.aliases.single().contains("ALIAS_TAIL"))
            assertFalse(firstSchema.contains("SCHEMA_TAIL"))
            assertFalse(firstSchema.contains("choice-$MAX_MODEL_TOOL_INPUT_SCHEMA_ENTRIES"))
        }

    @Test
    fun `remote provider turn fails fast when the device is offline`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAiCompatible,
                        ai.androidclaw.data.ProviderEndpointSettings(
                            baseUrl = "https://openai.example/v1",
                            modelId = "gpt-test",
                            timeoutSeconds = 30,
                        ),
                    ).copy(providerType = ProviderType.OpenAiCompatible),
            )
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider = failOnGenerateProvider(),
                            openAiCompatibleProvider = failOnGenerateProvider(),
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(ToolRegistry(emptyList())),
                    toolRegistry = ToolRegistry(emptyList()),
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                    networkStatusProvider =
                        object : NetworkStatusProvider {
                            override fun currentStatus(): NetworkStatusSnapshot =
                                NetworkStatusSnapshot(
                                    supported = true,
                                    isConnected = false,
                                    isValidated = false,
                                    isMetered = false,
                                )
                        },
                )

            val error =
                try {
                    runner.runInteractiveTurn(
                        AgentTurnRequest(
                            sessionId = sessionId,
                            userMessage = "Use the remote provider",
                        ),
                    )
                    error("Expected ModelProviderException.")
                } catch (error: ModelProviderException) {
                    error
                }

            val storedMessages = messageRepository.getRecentMessages(sessionId, limit = 10)

            assertEquals(ModelProviderFailureKind.Offline, error.kind)
            assertTrue(
                storedMessages.any { message ->
                    message.role == ai.androidclaw.data.model.MessageRole.System &&
                        message.content.contains("No active network connection")
                },
            )
        }

    @Test
    fun `openai provider tool call loop submits structured tool transcript and persists result`() =
        runTest {
            val server = MockWebServer()
            server.start()
            val secretStore =
                InMemoryProviderSecretStore(
                    initialSecrets = mapOf(ProviderType.OpenAiCompatible to "sk-test"),
                )
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAiCompatible,
                        ai.androidclaw.data.ProviderEndpointSettings(
                            baseUrl = server.url("/v1/").toString().removeSuffix("/"),
                            modelId = "gpt-test",
                            timeoutSeconds = 5,
                        ),
                    ).copy(providerType = ProviderType.OpenAiCompatible),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "resp-1",
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
                                      "arguments": "{}"
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
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "resp-2",
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "Final answer from OpenAI"
                              },
                              "finish_reason": "stop"
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

            try {
                val toolRegistry =
                    ToolRegistry(
                        tools =
                            listOf(
                                ToolRegistry.Entry(
                                    descriptor =
                                        ToolDescriptor(
                                            name = "health.status",
                                            description = "Report health",
                                        ),
                                ) { _, _ ->
                                    ToolExecutionResult.success(
                                        summary = "Health okay",
                                        payload =
                                            buildJsonObject {
                                                put("status", "ok")
                                            },
                                    )
                                },
                            ),
                    )
                val runner =
                    AgentRunner(
                        providerRegistry =
                            buildTestProviderRegistry(
                                fakeProvider = failOnGenerateProvider(),
                                openAiCompatibleProvider = buildOpenAiProvider(secretStore),
                            ),
                        settingsDataStore = settingsDataStore,
                        messageRepository = messageRepository,
                        skillManager = buildSkillManager(toolRegistry),
                        toolRegistry = toolRegistry,
                        sessionLaneCoordinator = SessionLaneCoordinator(),
                        promptAssembler = PromptAssembler(),
                    )

                val result =
                    runner.runInteractiveTurn(
                        AgentTurnRequest(
                            sessionId = sessionId,
                            userMessage = "Use the real provider path",
                        ),
                    )
                val requestOne = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Expected first provider request.")
                val requestTwo = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Expected second provider request.")
                val json = Json { ignoreUnknownKeys = true }
                val payloadOne = json.parseToJsonElement(requestOne.body.readUtf8()).jsonObject
                val payloadTwo = json.parseToJsonElement(requestTwo.body.readUtf8()).jsonObject
                val firstTools = payloadOne.getValue("tools").jsonArray
                val secondMessages = payloadTwo.getValue("messages").jsonArray
                val assistantToolCalls = secondMessages[2].jsonObject.getValue("tool_calls").jsonArray
                val toolMessage = secondMessages[3].jsonObject
                val storedMessages = messageRepository.getRecentMessages(sessionId, limit = 10)

                assertTrue(result.assistantMessage.contains("Final answer from OpenAI"))
                assertEquals(
                    "health.status",
                    firstTools
                        .single()
                        .jsonObject
                        .getValue("function")
                        .jsonObject
                        .getValue("name")
                        .jsonPrimitive.content,
                )
                assertEquals(
                    "health.status",
                    assistantToolCalls
                        .single()
                        .jsonObject
                        .getValue("function")
                        .jsonObject
                        .getValue("name")
                        .jsonPrimitive.content,
                )
                assertEquals("tool", toolMessage.getValue("role").jsonPrimitive.content)
                assertEquals("call-1", toolMessage.getValue("tool_call_id").jsonPrimitive.content)
                assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.ToolCall })
                assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.ToolResult })
                assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.Assistant && it.content.contains("Final answer from OpenAI") })
            } finally {
                settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
                server.shutdown()
            }
        }

    @Test
    fun `tool execution writes bounded tool event logs with context metadata`() =
        runTest {
            val toolRegistry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "tasks.list",
                                        description = "List tasks",
                                    ),
                            ) { context, _ ->
                                assertEquals(sessionId, context.sessionId)
                                assertEquals("list_tasks", context.activeSkillId)
                                ToolExecutionResult.success(
                                    summary = "Tasks tool reached",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                    eventLogger = { level, message, details ->
                        eventLogRepository.log(
                            category = EventCategory.Tool,
                            level = level,
                            message = message,
                            details = details,
                        )
                    },
                )
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider = failOnGenerateProvider(),
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(toolRegistry),
                    toolRegistry = toolRegistry,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            runner.runInteractiveTurn(
                AgentTurnRequest(
                    sessionId = sessionId,
                    userMessage = "/list_tasks pending",
                ),
            )

            val toolEvents =
                eventLogRepository
                    .observeRecent(limit = 10)
                    .first()
                    .filter { it.category == EventCategory.Tool }

            assertEquals(2, toolEvents.size)
            assertTrue(toolEvents.any { it.message.contains("started") })
            assertTrue(toolEvents.any { it.message.contains("completed") })
            assertTrue(toolEvents.all { it.details?.contains("\"sessionId\":\"$sessionId\"") == true })
            assertTrue(toolEvents.any { it.details?.contains("\"activeSkillId\":\"list_tasks\"") == true })
        }

    @Test
    fun `interactive stream fallback emits assistant delta and persists final assistant message`() =
        runTest {
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse(text = "Stream fallback reply")
                                },
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(ToolRegistry(emptyList())),
                    toolRegistry = ToolRegistry(emptyList()),
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            val events =
                runner
                    .runInteractiveTurnStream(
                        AgentTurnRequest(
                            sessionId = sessionId,
                            userMessage = "hello stream",
                        ),
                    ).toList()

            assertTrue(events.any { it == AgentTurnEvent.AssistantTextDelta("Stream fallback reply") })
            val completed = events.last() as AgentTurnEvent.TurnCompleted
            assertTrue(completed.result.assistantMessage.contains("Stream fallback reply"))
            val storedMessages = messageRepository.getRecentMessages(sessionId, limit = 10)
            assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.Assistant && it.content.contains("Stream fallback reply") })
        }

    @Test
    fun `interactive stream fallback bounds oversized preview delta and assistant result`() =
        runTest {
            val oversizedReply = "r".repeat(MESSAGE_CONTENT_MAX_CHARS + 2_000)
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse(text = oversizedReply)
                                },
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(ToolRegistry(emptyList())),
                    toolRegistry = ToolRegistry(emptyList()),
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            val events =
                runner
                    .runInteractiveTurnStream(
                        AgentTurnRequest(
                            sessionId = sessionId,
                            userMessage = "hello oversized fallback stream",
                        ),
                    ).toList()
            val previewDeltas = events.filterIsInstance<AgentTurnEvent.AssistantTextDelta>().map { it.text }
            val completed = events.last() as AgentTurnEvent.TurnCompleted
            val storedAssistant =
                messageRepository
                    .getRecentMessages(sessionId, limit = 10)
                    .first { it.role == ai.androidclaw.data.model.MessageRole.Assistant }

            assertEquals(AGENT_STREAMING_PREVIEW_MAX_CHARS, previewDeltas.sumOf(String::length))
            assertEquals(AGENT_STREAMING_PREVIEW_TRUNCATED_NOTICE, previewDeltas.last())
            assertTrue(previewDeltas.none { it.length > AGENT_STREAMING_PREVIEW_MAX_CHARS })
            assertEquals(MESSAGE_CONTENT_MAX_CHARS, completed.result.assistantMessage.length)
            assertEquals(MESSAGE_CONTENT_MAX_CHARS, storedAssistant.content.length)
        }

    @Test
    fun `interactive streamed text accumulation is bounded before persistence`() =
        runTest {
            val oversizedStreamDelta = "s".repeat(MESSAGE_CONTENT_MAX_CHARS + 2_000)
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse(text = "unused")

                                    override fun streamGenerate(request: ModelRequest) =
                                        flow {
                                            emit(ModelStreamEvent.TextDelta(oversizedStreamDelta))
                                            emit(ModelStreamEvent.Completed(ModelResponse(text = "")))
                                        }
                                },
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(ToolRegistry(emptyList())),
                    toolRegistry = ToolRegistry(emptyList()),
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            val events =
                runner
                    .runInteractiveTurnStream(
                        AgentTurnRequest(
                            sessionId = sessionId,
                            userMessage = "hello oversized streamed text",
                        ),
                    ).toList()
            val previewDeltas = events.filterIsInstance<AgentTurnEvent.AssistantTextDelta>().map { it.text }
            val completed = events.last() as AgentTurnEvent.TurnCompleted
            val storedAssistant =
                messageRepository
                    .getRecentMessages(sessionId, limit = 10)
                    .first { it.role == ai.androidclaw.data.model.MessageRole.Assistant }

            assertEquals(AGENT_STREAMING_PREVIEW_MAX_CHARS, previewDeltas.sumOf(String::length))
            assertEquals(AGENT_STREAMING_PREVIEW_TRUNCATED_NOTICE, previewDeltas.last())
            assertEquals(MESSAGE_CONTENT_MAX_CHARS, completed.result.assistantMessage.length)
            assertEquals(MESSAGE_CONTENT_MAX_CHARS, storedAssistant.content.length)
        }

    @Test
    fun `cancelling interactive stream propagates cancellation and releases the session lane`() =
        runTest {
            val cancelled = CompletableDeferred<Unit>()
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse(text = "Recovered after cancel")

                                    override fun streamGenerate(request: ModelRequest) =
                                        kotlinx.coroutines.flow.flow {
                                            emit(ModelStreamEvent.TextDelta("partial"))
                                            try {
                                                awaitCancellation()
                                            } finally {
                                                cancelled.complete(Unit)
                                            }
                                        }
                                },
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(ToolRegistry(emptyList())),
                    toolRegistry = ToolRegistry(emptyList()),
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            val firstEvents =
                runner
                    .runInteractiveTurnStream(
                        AgentTurnRequest(
                            sessionId = sessionId,
                            userMessage = "cancel me",
                        ),
                    ).take(1)
                    .toList()

            withTimeout(5_000) {
                cancelled.await()
            }

            val result =
                runner.runInteractiveTurn(
                    AgentTurnRequest(
                        sessionId = sessionId,
                        userMessage = "second turn",
                    ),
                )
            val storedMessages = messageRepository.getRecentMessages(sessionId, limit = 10)

            assertEquals(listOf(AgentTurnEvent.AssistantTextDelta("partial")), firstEvents)
            assertTrue(result.assistantMessage.contains("Recovered after cancel"))
            assertTrue(
                storedMessages.any { message ->
                    message.role == ai.androidclaw.data.model.MessageRole.System &&
                        message.content == "Turn cancelled."
                },
            )
        }

    @Test
    fun `provider tool call can create a task through the built in task tools`() =
        runTest {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                application,
                Configuration.Builder().build(),
            )
            val testClock = Clock.fixed(Instant.parse("2026-03-08T00:00:00Z"), ZoneOffset.UTC)
            val schedulerCoordinator =
                SchedulerCoordinator(
                    application = application,
                    clock = testClock,
                    taskRepository = taskRepository,
                    eventLogRepository = eventLogRepository,
                )
            val toolRegistry =
                createBuiltInToolRegistry(
                    application = application,
                    settingsDataStore = settingsDataStore,
                    sessionRepository = sessionRepository,
                    taskRepository = taskRepository,
                    schedulerCoordinator = schedulerCoordinator,
                    bundledSkillsProvider = { emptyList() },
                    messageRepository = messageRepository,
                    eventLogRepository = eventLogRepository,
                    clock = testClock,
                )
            var providerCalls = 0
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse {
                                        providerCalls += 1
                                        return if (providerCalls == 1) {
                                            ModelResponse(
                                                text = "",
                                                finishReason = "tool_use",
                                                toolCalls =
                                                    listOf(
                                                        ProviderToolCall(
                                                            id = "call-create",
                                                            name = "tasks.create",
                                                            argumentsJson =
                                                                buildJsonObject {
                                                                    put("name", "Morning summary")
                                                                    put("prompt", "Summarize today")
                                                                    put("scheduleKind", "once")
                                                                    put("atIso", "2026-03-20T08:00:00Z")
                                                                    put("targetSessionAlias", "current")
                                                                },
                                                        ),
                                                    ),
                                            )
                                        } else {
                                            ModelResponse(
                                                text = "Created the task.",
                                                finishReason = "stop",
                                            )
                                        }
                                    }
                                },
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(toolRegistry),
                    toolRegistry = toolRegistry,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            val result =
                runner.runInteractiveTurn(
                    AgentTurnRequest(
                        sessionId = sessionId,
                        userMessage = "Create a task for tomorrow morning.",
                    ),
                )

            val tasks = taskRepository.observeTasks().first()
            assertEquals(1, tasks.size)
            assertEquals("Morning summary", tasks.single().name)
            assertEquals(sessionId, tasks.single().targetSessionId)
            assertTrue(result.assistantMessage.contains("Created the task."))
        }

    @Test
    fun `provider tool call count is validated before execution`() =
        runTest {
            var handlerCalled = false
            val toolRegistry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "health.status",
                                        description = "Check health",
                                    ),
                            ) { _, _ ->
                                handlerCalled = true
                                ToolExecutionResult.success(
                                    summary = "should not run",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse =
                                        ModelResponse(
                                            text = "",
                                            finishReason = "tool_use",
                                            toolCalls =
                                                (1..(AGENT_PROVIDER_TOOL_CALL_MAX_COUNT + 1)).map { index ->
                                                    ProviderToolCall(
                                                        id = "call-$index",
                                                        name = "health.status",
                                                        argumentsJson = buildJsonObject {},
                                                    )
                                                },
                                        )
                                },
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(toolRegistry, skills = emptyList()),
                    toolRegistry = toolRegistry,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            val error =
                runCatching {
                    runner.runInteractiveTurn(
                        AgentTurnRequest(
                            sessionId = sessionId,
                            userMessage = "call too many tools",
                        ),
                    )
                }.exceptionOrNull()
            val storedMessages = messageRepository.getRecentMessages(sessionId, limit = 20)

            assertTrue(error is ModelProviderException)
            val providerError = error as ModelProviderException
            assertEquals(ModelProviderFailureKind.Response, providerError.kind)
            assertEquals("Provider returned too many tool calls.", providerError.userMessage)
            assertFalse(handlerCalled)
            assertTrue(storedMessages.none { it.role == ai.androidclaw.data.model.MessageRole.ToolCall })
            assertTrue(storedMessages.any { it.content.contains("Provider returned too many tool calls.") })
        }

    @Test
    fun `provider tool argument size is validated before persistence and execution`() =
        runTest {
            var handlerCalled = false
            val toolRegistry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "health.status",
                                        description = "Check health",
                                    ),
                            ) { _, _ ->
                                handlerCalled = true
                                ToolExecutionResult.success(
                                    summary = "should not run",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )
            val oversizedArguments =
                buildJsonObject {
                    put("payload", "x".repeat(AGENT_PROVIDER_TOOL_ARGUMENT_JSON_MAX_CHARS + 1))
                }
            val runner =
                AgentRunner(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse =
                                        ModelResponse(
                                            text = "",
                                            finishReason = "tool_use",
                                            toolCalls =
                                                listOf(
                                                    ProviderToolCall(
                                                        id = "call-oversized",
                                                        name = "health.status",
                                                        argumentsJson = oversizedArguments,
                                                    ),
                                                ),
                                        )
                                },
                        ),
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = buildSkillManager(toolRegistry, skills = emptyList()),
                    toolRegistry = toolRegistry,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                )

            val error =
                runCatching {
                    runner.runInteractiveTurn(
                        AgentTurnRequest(
                            sessionId = sessionId,
                            userMessage = "call tool with huge args",
                        ),
                    )
                }.exceptionOrNull()
            val storedMessages = messageRepository.getRecentMessages(sessionId, limit = 20)

            assertTrue(error is ModelProviderException)
            val providerError = error as ModelProviderException
            assertEquals(ModelProviderFailureKind.Response, providerError.kind)
            assertEquals("Provider returned oversized tool arguments.", providerError.userMessage)
            assertFalse(handlerCalled)
            assertTrue(storedMessages.none { it.role == ai.androidclaw.data.model.MessageRole.ToolCall })
            assertTrue(storedMessages.any { it.content.contains("Provider returned oversized tool arguments.") })
        }

    private fun buildSkillManager(
        toolRegistry: ToolRegistry,
        skills: List<SkillSnapshot> =
            listOf(
                skillSnapshot(
                    id = "list_tasks",
                    name = "list_tasks",
                    commandDispatch = SkillCommandDispatch.Tool,
                    commandTool = "tasks.list",
                ),
            ),
    ): SkillManager =
        createTestSkillManager(
            application = application,
            skillRepository =
                ai.androidclaw.data.repository
                    .SkillRepository(database.skillRecordDao()),
            toolDescriptor = toolRegistry::findDescriptor,
            bundledSkillLoader =
                StaticBundledSkillLoader(
                    assetManager = application.assets,
                    skills = skills,
                ),
        )

    private fun buildOpenAiProvider(secretStore: ProviderSecretStore): OpenAiCompatibleProvider =
        OpenAiCompatibleProvider(
            providerType = ProviderType.OpenAiCompatible,
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
            baseHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
        )
}

private fun failOnGenerateProvider(): ModelProvider =
    object : ModelProvider {
        override val id: String = "fake"

        override suspend fun generate(request: ModelRequest): ModelResponse {
            error("Provider should not have been called.")
        }
    }

private class StaticBundledSkillLoader(
    assetManager: AssetManager,
    private val skills: List<SkillSnapshot>,
) : BundledSkillLoader(
        assetManager = assetManager,
        rootPath = "skills",
        parser = SkillParser(),
    ) {
    override suspend fun load(): List<SkillSnapshot> = skills
}

private fun skillSnapshot(
    id: String,
    name: String,
    commandDispatch: SkillCommandDispatch,
    commandTool: String,
): SkillSnapshot =
    SkillSnapshot(
        id = id,
        skillKey = name,
        sourceType = SkillSourceType.Bundled,
        baseDir = "asset://skills/$id",
        enabled = true,
        frontmatter =
            SkillFrontmatter(
                name = name,
                description = "Description for $name",
                homepage = null,
                userInvocable = true,
                disableModelInvocation = false,
                commandDispatch = commandDispatch,
                commandTool = commandTool,
                commandArgMode = "raw",
                metadata = null,
                unknownFields = emptyMap(),
            ),
        instructionsMd = "Use the tool directly.",
        eligibility =
            SkillEligibility(
                status = SkillEligibilityStatus.Eligible,
            ),
    )
