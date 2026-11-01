package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.runtime.providers.ModelMessageRole
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelProviderException
import ai.androidclaw.runtime.providers.ModelProviderFailureKind
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.providers.ProviderToolCall
import ai.androidclaw.runtime.skills.createTestSkillManager
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Agent-loop guarantees: payload delivery, bounded rounds, cancellation, ids, readable errors. */
@RunWith(AndroidJUnit4::class)
class AgentRunnerLoopTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var messageRepository: MessageRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var sessionId: String
    private val application: android.app.Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() =
        runTest {
            database = buildTestDatabase(application)
            messageRepository = MessageRepository(database.messageDao())
            settingsDataStore = SettingsDataStore(application)
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            sessionId = SessionRepository(database.sessionDao()).createSession("Loop test").id
        }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `tool payload reaches the model and the persisted transcript`() =
        runTest {
            val requests = CopyOnWriteArrayList<ModelRequest>()
            val provider =
                scriptedProvider(requests) { index ->
                    if (index == 0) toolCallResponse(ProviderToolCall("call-1", "read_note", buildJsonObject {})) else ModelResponse("done")
                }
            val tools =
                ToolRegistry(
                    listOf(
                        ToolRegistry.Entry(ToolDescriptor(name = "read_note", description = "Read a note")) { _, _ ->
                            ToolExecutionResult.success("Read note.md", buildJsonObject { put("text", "NOTE_BODY_123") })
                        },
                    ),
                )

            val result = runner(provider, tools).runInteractiveTurn(AgentTurnRequest(sessionId, "read it"))

            assertEquals("done", result.assistantMessage)
            val toolMessage = requests[1].messageHistory.last { it.role == ModelMessageRole.Tool }
            assertTrue(toolMessage.content.contains("NOTE_BODY_123"))
            val persisted = messageRepository.getMessages(sessionId).single { it.role == MessageRole.ToolResult }
            assertTrue(persisted.content.startsWith("Tool result: Read note.md"))
            assertTrue(persisted.content.contains("NOTE_BODY_123"))
        }

    @Test
    fun `workspace context is injected into the system prompt`() =
        runTest {
            val requests = CopyOnWriteArrayList<ModelRequest>()
            val runner =
                runner(scriptedProvider(requests) { ModelResponse("ok") }, ToolRegistry(emptyList()), workspace = { "## USER.md\nName: Li" })

            runner.runInteractiveTurn(AgentTurnRequest(sessionId, "hi"))

            assertTrue(requests.single().systemPrompt.contains("## USER.md\nName: Li"))
        }

    @Test
    fun `tool loop stops after the round limit`() =
        runTest {
            var counter = 0
            val provider =
                scriptedProvider(CopyOnWriteArrayList()) {
                    counter++
                    toolCallResponse(ProviderToolCall("call-$counter", "noop", buildJsonObject {}))
                }
            val tools =
                ToolRegistry(
                    listOf(
                        ToolRegistry.Entry(ToolDescriptor(name = "noop", description = "No-op")) { _, _ ->
                            ToolExecutionResult.success("ok", buildJsonObject {})
                        },
                    ),
                )

            val result = runner(provider, tools).runInteractiveTurn(AgentTurnRequest(sessionId, "loop"))

            assertEquals(AgentTurnExitReason.ToolLoopExhausted, result.exitReason)
            val stored = messageRepository.getMessages(sessionId)
            assertEquals(AgentRunner.MAX_TOOL_ROUNDS, stored.count { it.role == MessageRole.ToolCall })
            assertEquals(AgentRunner.MAX_TOOL_ROUNDS, stored.count { it.role == MessageRole.ToolResult })
        }

    @Test
    fun `duplicate provider tool call ids get one result row each`() =
        runTest {
            val provider =
                scriptedProvider(CopyOnWriteArrayList()) { index ->
                    if (index == 0) {
                        toolCallResponse(
                            ProviderToolCall("dup", "noop", buildJsonObject {}),
                            ProviderToolCall("dup", "noop", buildJsonObject {}),
                        )
                    } else {
                        ModelResponse("done")
                    }
                }
            val tools =
                ToolRegistry(
                    listOf(
                        ToolRegistry.Entry(ToolDescriptor(name = "noop", description = "No-op")) { _, _ ->
                            ToolExecutionResult.success("ok", buildJsonObject {})
                        },
                    ),
                )

            runner(provider, tools).runInteractiveTurn(AgentTurnRequest(sessionId, "twice"))

            val results = messageRepository.getMessages(sessionId).filter { it.role == MessageRole.ToolResult }
            assertEquals(listOf("dup", "dup-2"), results.map { it.toolCallId })
        }

    @Test
    fun `cancelling during a tool still persists a result row for the call`() =
        runTest {
            val toolStarted = CompletableDeferred<Unit>()
            val provider =
                scriptedProvider(CopyOnWriteArrayList()) {
                    toolCallResponse(ProviderToolCall("slow-1", "slow", buildJsonObject {}))
                }
            val tools =
                ToolRegistry(
                    listOf(
                        ToolRegistry.Entry(ToolDescriptor(name = "slow", description = "Slow")) { _, _ ->
                            toolStarted.complete(Unit)
                            awaitCancellation()
                        },
                    ),
                )
            val runner = runner(provider, tools)

            val job = launch { runner.runInteractiveTurn(AgentTurnRequest(sessionId, "start slow")) }
            toolStarted.await()
            job.cancelAndJoin()

            val stored = messageRepository.getMessages(sessionId)
            val call = stored.single { it.role == MessageRole.ToolCall }
            val result = stored.single { it.role == MessageRole.ToolResult }
            assertEquals(call.toolCallId, result.toolCallId)
            assertEquals("Tool result: Cancelled before the tool finished.\n{\"errorCode\":\"CANCELLED\"}", result.content)
        }

    @Test
    fun `provider failure persists a readable turn failed notice`() =
        runTest {
            val provider =
                object : ModelProvider {
                    override val id: String = "fake"

                    override suspend fun generate(request: ModelRequest): ModelResponse = throw ModelProviderException(ModelProviderFailureKind.Authentication, "The API key was rejected.")
                }

            val error =
                runCatching {
                    runner(provider, ToolRegistry(emptyList())).runInteractiveTurn(AgentTurnRequest(sessionId, "hi"))
                }.exceptionOrNull()

            assertTrue(error is ModelProviderException)
            val notice = messageRepository.getMessages(sessionId).last()
            assertEquals(MessageRole.System, notice.role)
            assertEquals("Turn failed: The API key was rejected.", notice.content)
        }

    private fun runner(
        provider: ModelProvider,
        tools: ToolRegistry,
        workspace: (suspend () -> String?)? = null,
    ): AgentRunner =
        AgentRunner(
            providerRegistry = buildTestProviderRegistry(fakeProvider = provider),
            settingsDataStore = settingsDataStore,
            messageRepository = messageRepository,
            skillManager =
                createTestSkillManager(
                    application = application,
                    skillRepository = SkillRepository(database.skillRecordDao()),
                    toolDescriptor = tools::findDescriptor,
                ),
            toolRegistry = tools,
            sessionLaneCoordinator = SessionLaneCoordinator(),
            promptAssembler = PromptAssembler(),
            workspaceContextProvider = workspace,
        )
}

private fun scriptedProvider(
    requests: MutableList<ModelRequest>,
    respond: (Int) -> ModelResponse,
): ModelProvider =
    object : ModelProvider {
        override val id: String = "fake"

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += request
            return respond(requests.size - 1)
        }
    }

private fun toolCallResponse(vararg calls: ProviderToolCall): ModelResponse = ModelResponse(text = "", finishReason = "tool_use", toolCalls = calls.toList())
