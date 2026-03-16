package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.skills.BundledSkillLoader
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibility
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelStreamEvent
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.providers.OpenAiCompatibleProvider
import ai.androidclaw.runtime.providers.ProviderToolCall
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillParser
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.skills.SkillSourceType
import ai.androidclaw.runtime.skills.createTestSkillManager
import ai.androidclaw.runtime.tools.createBuiltInToolRegistry
import ai.androidclaw.runtime.tools.ToolAvailability
import ai.androidclaw.runtime.tools.ToolAvailabilityStatus
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolPermissionRequirement
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.testutil.InMemoryProviderSecretStore
import ai.androidclaw.testutil.buildTestProviderRegistry
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentRunnerTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var messageRepository: MessageRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var eventLogRepository: EventLogRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var sessionId: String

    @Before
    fun setUp() = runTest {
        application = ApplicationProvider.getApplicationContext()
        database = buildTestDatabase(application)
        messageRepository = MessageRepository(database.messageDao())
        sessionRepository = SessionRepository(database.sessionDao())
        eventLogRepository = EventLogRepository(database.eventLogDao())
        taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
        settingsDataStore = SettingsDataStore(application)
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        sessionId = sessionRepository.createSession("Test session").id
    }

    @After
    fun tearDown() = runTest {
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        database.close()
    }

    @Test
    fun `slash tool skill executes direct tool path without provider`() = runTest {
        val toolRegistry = ToolRegistry(
            tools = listOf(
                ToolRegistry.Entry(
                    descriptor = ToolDescriptor(
                        name = "tasks.list",
                        description = "List tasks",
                    ),
                ) { _, arguments ->
                    ToolExecutionResult.success(
                        summary = "Tasks tool reached",
                        payload = buildJsonObject {
                            put("command", arguments["command"]?.jsonPrimitive?.content.orEmpty())
                        },
                    )
                },
            ),
        )
        val skillManager = buildSkillManager(toolRegistry)
        val runner = AgentRunner(
            providerRegistry = buildTestProviderRegistry(
                fakeProvider = failOnGenerateProvider(),
            ),
            settingsDataStore = settingsDataStore,
            messageRepository = messageRepository,
            skillManager = skillManager,
            toolRegistry = toolRegistry,
            sessionLaneCoordinator = SessionLaneCoordinator(),
            promptAssembler = PromptAssembler(),
        )

        val result = runner.runInteractiveTurn(
            AgentTurnRequest(
                sessionId = sessionId,
                userMessage = "/list_tasks pending",
            ),
        )

        assertTrue(result.assistantMessage.contains("Tasks tool reached"))
        assertEquals(listOf("list_tasks"), result.selectedSkills.map { it.displayName })
        assertNotNull(result.directToolResult)
        assertEquals("pending", result.directToolResult?.payload?.get("command")?.jsonPrimitive?.content)
        assertNull(result.providerRequestId)
        val storedMessages = messageRepository.getRecentMessages(sessionId, limit = 10)
        assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.ToolCall })
        assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.ToolResult })
        assertTrue(storedMessages.any { it.role == ai.androidclaw.data.model.MessageRole.Assistant })
    }

    @Test
    fun `blocked slash skill returns eligibility reason instead of falling through to provider`() = runTest {
        val toolRegistry = ToolRegistry(
            tools = listOf(
                ToolRegistry.Entry(
                    descriptor = ToolDescriptor(
                        name = "tasks.list",
                        description = "List tasks",
                        requiredPermissions = listOf(
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
        val runner = AgentRunner(
            providerRegistry = buildTestProviderRegistry(
                fakeProvider = failOnGenerateProvider(),
            ),
            settingsDataStore = settingsDataStore,
            messageRepository = messageRepository,
            skillManager = skillManager,
            toolRegistry = toolRegistry,
            sessionLaneCoordinator = SessionLaneCoordinator(),
            promptAssembler = PromptAssembler(),
        )

        val result = runner.runInteractiveTurn(
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
    fun `provider-driven tool call loop persists tool messages and final assistant response`() = runTest {
        val toolRegistry = ToolRegistry(
            tools = listOf(
                ToolRegistry.Entry(
                    descriptor = ToolDescriptor(
                        name = "health.status",
                        description = "Report health",
                    ),
                ) { _, _ ->
                    ToolExecutionResult.success(
                        summary = "Health okay",
                        payload = buildJsonObject {
                            put("status", "ok")
                        },
                    )
                },
            ),
        )
        val runner = AgentRunner(
            providerRegistry = buildTestProviderRegistry(),
            settingsDataStore = settingsDataStore,
            messageRepository = messageRepository,
            skillManager = buildSkillManager(toolRegistry),
            toolRegistry = toolRegistry,
            sessionLaneCoordinator = SessionLaneCoordinator(),
            promptAssembler = PromptAssembler(),
        )

        val result = runner.runInteractiveTurn(
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
    fun `openai provider tool call loop submits structured tool transcript and persists result`() = runTest {
        val server = MockWebServer()
        server.start()
        val secretStore = InMemoryProviderSecretStore(
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
                )
                .copy(providerType = ProviderType.OpenAiCompatible),
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
            val toolRegistry = ToolRegistry(
                tools = listOf(
                ToolRegistry.Entry(
                    descriptor = ToolDescriptor(
                        name = "health.status",
                        description = "Report health",
                    ),
                ) { _, _ ->
                    ToolExecutionResult.success(
                        summary = "Health okay",
                        payload = buildJsonObject {
                            put("status", "ok")
                            },
                        )
                    },
                ),
            )
            val runner = AgentRunner(
                providerRegistry = buildTestProviderRegistry(
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

            val result = runner.runInteractiveTurn(
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
            assertEquals("health.status", firstTools.single().jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals("health.status", assistantToolCalls.single().jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
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
    fun `tool execution writes bounded tool event logs with context metadata`() = runTest {
        val toolRegistry = ToolRegistry(
            tools = listOf(
                ToolRegistry.Entry(
                    descriptor = ToolDescriptor(
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
        val runner = AgentRunner(
            providerRegistry = buildTestProviderRegistry(
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

        val toolEvents = eventLogRepository.observeRecent(limit = 10).first()
            .filter { it.category == EventCategory.Tool }

        assertEquals(2, toolEvents.size)
        assertTrue(toolEvents.any { it.message.contains("started") })
        assertTrue(toolEvents.any { it.message.contains("completed") })
        assertTrue(toolEvents.all { it.details?.contains("\"sessionId\":\"$sessionId\"") == true })
        assertTrue(toolEvents.any { it.details?.contains("\"activeSkillId\":\"list_tasks\"") == true })
    }

    @Test
    fun `interactive stream fallback emits assistant delta and persists final assistant message`() = runTest {
        val runner = AgentRunner(
            providerRegistry = buildTestProviderRegistry(
                fakeProvider = object : ModelProvider {
                    override val id: String = "fake"

                    override suspend fun generate(request: ModelRequest): ModelResponse {
                        return ModelResponse(text = "Stream fallback reply")
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

        val events = runner.runInteractiveTurnStream(
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
    fun `cancelling interactive stream propagates cancellation and releases the session lane`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val runner = AgentRunner(
            providerRegistry = buildTestProviderRegistry(
                fakeProvider = object : ModelProvider {
                    override val id: String = "fake"

                    override suspend fun generate(request: ModelRequest): ModelResponse {
                        return ModelResponse(text = "Recovered after cancel")
                    }

                    override fun streamGenerate(request: ModelRequest) = kotlinx.coroutines.flow.flow {
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

        val firstEvents = runner.runInteractiveTurnStream(
            AgentTurnRequest(
                sessionId = sessionId,
                userMessage = "cancel me",
            ),
        ).take(1).toList()

        withTimeout(5_000) {
            cancelled.await()
        }

        val result = runner.runInteractiveTurn(
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
    fun `provider tool call can create a task through the built in task tools`() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            application,
            Configuration.Builder().build(),
        )
        val schedulerCoordinator = SchedulerCoordinator(
            application = application,
            clock = Clock.fixed(Instant.parse("2026-03-08T00:00:00Z"), ZoneOffset.UTC),
            taskRepository = taskRepository,
            eventLogRepository = eventLogRepository,
        )
        val toolRegistry = createBuiltInToolRegistry(
            application = application,
            settingsDataStore = settingsDataStore,
            sessionRepository = sessionRepository,
            taskRepository = taskRepository,
            schedulerCoordinator = schedulerCoordinator,
            bundledSkillsProvider = { emptyList() },
            eventLogRepository = eventLogRepository,
        )
        var providerCalls = 0
        val runner = AgentRunner(
            providerRegistry = buildTestProviderRegistry(
                fakeProvider = object : ModelProvider {
                    override val id: String = "fake"

                    override suspend fun generate(request: ModelRequest): ModelResponse {
                        providerCalls += 1
                        return if (providerCalls == 1) {
                            ModelResponse(
                                text = "",
                                finishReason = "tool_use",
                                toolCalls = listOf(
                                    ProviderToolCall(
                                        id = "call-create",
                                        name = "tasks.create",
                                        argumentsJson = buildJsonObject {
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

        val result = runner.runInteractiveTurn(
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

    private fun buildSkillManager(toolRegistry: ToolRegistry): SkillManager {
        return createTestSkillManager(
            application = application,
            skillRepository = ai.androidclaw.data.repository.SkillRepository(database.skillRecordDao()),
            toolDescriptor = toolRegistry::findDescriptor,
            bundledSkillLoader = StaticBundledSkillLoader(
                assetManager = application.assets,
            skills = listOf(
                skillSnapshot(
                    id = "list_tasks",
                    name = "list_tasks",
                        commandDispatch = SkillCommandDispatch.Tool,
                        commandTool = "tasks.list",
                    ),
                ),
            ),
        )
    }

    private fun buildOpenAiProvider(secretStore: ProviderSecretStore): OpenAiCompatibleProvider {
        return OpenAiCompatibleProvider(
            providerType = ProviderType.OpenAiCompatible,
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
            baseHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
        )
    }
}

private fun failOnGenerateProvider(): ModelProvider {
    return object : ModelProvider {
        override val id: String = "fake"

        override suspend fun generate(request: ModelRequest): ModelResponse {
            error("Provider should not have been called.")
        }
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
): SkillSnapshot {
    return SkillSnapshot(
        id = id,
        skillKey = name,
        sourceType = SkillSourceType.Bundled,
        baseDir = "asset://skills/$id",
        enabled = true,
        frontmatter = SkillFrontmatter(
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
        eligibility = SkillEligibility(
            status = SkillEligibilityStatus.Eligible,
        ),
    )
}
