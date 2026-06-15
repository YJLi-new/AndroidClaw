package ai.androidclaw.feature.chat

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.orchestrator.PromptAssembler
import ai.androidclaw.runtime.orchestrator.SessionLaneCoordinator
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelProviderException
import ai.androidclaw.runtime.providers.ModelProviderFailureKind
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.providers.ModelStreamEvent
import ai.androidclaw.runtime.providers.ProviderMessageMeta
import ai.androidclaw.runtime.providers.ProviderUsagePayload
import ai.androidclaw.runtime.providers.toStorageString
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.createTestSkillManager
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.testutil.MainDispatcherRule
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: AndroidClawDatabase
    private lateinit var sessionRepository: SessionRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var eventLogRepository: EventLogRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var skillManager: SkillManager
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() =
        runTest {
            val application = ApplicationProvider.getApplicationContext<android.app.Application>()
            database = buildTestDatabase(application)
            sessionRepository = SessionRepository(database.sessionDao())
            messageRepository = MessageRepository(database.messageDao())
            eventLogRepository = EventLogRepository(database.eventLogDao())
            settingsDataStore = SettingsDataStore(application)
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())

            val toolRegistry = ToolRegistry(emptyList())
            skillManager =
                buildSkillManager(
                    application,
                    toolRegistry,
                    skillRepository =
                        ai.androidclaw.data.repository
                            .SkillRepository(database.skillRecordDao()),
                )
            viewModel =
                buildViewModel(
                    toolRegistry = toolRegistry,
                    skillManager = skillManager,
                )
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            database.close()
        }

    @Test
    fun `sending a message exposes streaming text before the assistant message is persisted`() =
        runTest {
            viewModel =
                buildViewModel(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse(text = "Reply: hello")

                                    override fun streamGenerate(request: ModelRequest) =
                                        flow {
                                            emit(ModelStreamEvent.TextDelta("Reply: "))
                                            delay(1)
                                            emit(ModelStreamEvent.Completed(ModelResponse(text = "Reply: hello")))
                                        }
                                },
                        ),
                )

            viewModel.state.test {
                val ready = awaitState { it.currentSessionId.isNotBlank() && it.sessions.isNotEmpty() }

                viewModel.onDraftChanged("hello")
                viewModel.sendCurrentDraft()

                awaitState { state ->
                    state.isRunning &&
                        state.streamingAssistantText == "Reply: " &&
                        state.activeTurnStage == "Generating response"
                }

                val completed =
                    awaitState { state ->
                        !state.isRunning &&
                            state.streamingAssistantText.isEmpty() &&
                            state.messages.any { it.role == "assistant" && it.text.contains("Reply: hello") }
                    }

                val stored = messageRepository.observeMessages(ready.currentSessionId).first()
                assertEquals(ready.currentSessionId, completed.currentSessionId)
                assertTrue(stored.any { it.role == MessageRole.User && it.content == "hello" })
                assertTrue(stored.any { it.role == MessageRole.Assistant && it.content.contains("Reply: hello") })
            }
        }

    @Test
    fun `draft input is bounded before it reaches Compose state`() =
        runTest {
            val oversizedDraft = "d".repeat(CHAT_DRAFT_MAX_CHARS + 2_000)

            viewModel.state.test {
                awaitState { it.currentSessionId.isNotBlank() && it.sessions.isNotEmpty() }

                viewModel.onDraftChanged(oversizedDraft)

                val bounded =
                    awaitState {
                        it.draft.length == CHAT_DRAFT_MAX_CHARS &&
                            it.noticeMessage == CHAT_DRAFT_TRUNCATED_NOTICE
                    }

                assertEquals(CHAT_DRAFT_MAX_CHARS, bounded.draft.length)
                assertEquals(CHAT_DRAFT_TRUNCATED_NOTICE, bounded.noticeMessage)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `provider failures expose retry and retry does not duplicate the user message`() =
        runTest {
            var calls = 0
            viewModel =
                buildViewModel(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse {
                                        calls += 1
                                        if (calls == 1) {
                                            throw ModelProviderException(
                                                kind = ModelProviderFailureKind.Network,
                                                userMessage = "Provider unavailable",
                                            )
                                        }
                                        return ModelResponse(text = "Recovered reply")
                                    }
                                },
                        ),
                )

            viewModel.state.test {
                val ready = awaitState { it.currentSessionId.isNotBlank() && it.sessions.isNotEmpty() }

                viewModel.onDraftChanged("hello")
                viewModel.sendCurrentDraft()

                awaitState { state ->
                    !state.isRunning &&
                        state.errorMessage == "Provider unavailable" &&
                        state.canRetryLastFailedTurn &&
                        state.messages.any { it.role == "system" && it.text.contains("Turn failed") }
                }

                viewModel.retryLastFailedTurn()

                val retried =
                    awaitState { state ->
                        !state.isRunning &&
                            !state.canRetryLastFailedTurn &&
                            state.messages.any { it.role == "assistant" && it.text.contains("Recovered reply") }
                    }

                val stored = messageRepository.observeMessages(ready.currentSessionId).first()
                val userMessages = stored.filter { it.role == MessageRole.User && it.content == "hello" }
                val events = eventLogRepository.observeRecent(limit = 20).first { it.isNotEmpty() }
                val providerFailureEvent = events.first { it.category == EventCategory.Provider && it.message == "Provider unavailable" }

                assertEquals(1, userMessages.size)
                assertTrue(retried.messages.any { it.role == "assistant" && it.text.contains("Recovered reply") })
                assertTrue(providerFailureEvent.details.orEmpty().contains("kind=Network"))
                assertTrue(providerFailureEvent.details.orEmpty().contains("retryable=true"))
                assertTrue(events.any { it.category == EventCategory.Provider && it.message == "Retrying failed turn." })
            }
        }

    @Test
    fun `cancelActiveTurn clears transient state without persisting a partial assistant message`() =
        runTest {
            viewModel =
                buildViewModel(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse(text = "unused")

                                    override fun streamGenerate(request: ModelRequest) =
                                        flow {
                                            emit(ModelStreamEvent.TextDelta("partial"))
                                            awaitCancellation()
                                        }
                                },
                        ),
                )

            viewModel.state.test {
                val ready = awaitState { it.currentSessionId.isNotBlank() && it.sessions.isNotEmpty() }

                viewModel.onDraftChanged("hello")
                viewModel.sendCurrentDraft()

                awaitState { state ->
                    state.isRunning &&
                        state.streamingAssistantText == "partial"
                }

                viewModel.cancelActiveTurn()

                val cancelled =
                    awaitState { state ->
                        !state.isRunning &&
                            state.noticeMessage == "Turn cancelled." &&
                            state.streamingAssistantText.isEmpty() &&
                            state.messages.any { it.role == "system" && it.text == "Turn cancelled." }
                    }

                val stored = messageRepository.observeMessages(ready.currentSessionId).first()
                assertTrue(cancelled.noticeMessage == "Turn cancelled.")
                assertTrue(stored.any { it.role == MessageRole.User && it.content == "hello" })
                assertTrue(stored.none { it.role == MessageRole.Assistant && it.content.contains("partial") })
            }
        }

    @Test
    fun `streaming assistant preview is bounded while turn is running`() =
        runTest {
            viewModel =
                buildViewModel(
                    providerRegistry =
                        buildTestProviderRegistry(
                            fakeProvider =
                                object : ModelProvider {
                                    override val id: String = "fake"

                                    override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse(text = "unused")

                                    override fun streamGenerate(request: ModelRequest) =
                                        flow {
                                            emit(
                                                ModelStreamEvent.TextDelta(
                                                    "x".repeat(CHAT_STREAMING_ASSISTANT_TEXT_MAX_CHARS + 2_000),
                                                ),
                                            )
                                            emit(ModelStreamEvent.TextDelta("more text that should not grow the preview"))
                                            awaitCancellation()
                                        }
                                },
                        ),
                )

            viewModel.state.test {
                val ready = awaitState { it.currentSessionId.isNotBlank() && it.sessions.isNotEmpty() }

                viewModel.onDraftChanged("hello")
                viewModel.sendCurrentDraft()

                val streaming =
                    awaitState { state ->
                        state.isRunning &&
                            state.streamingAssistantText.endsWith(CHAT_STREAMING_ASSISTANT_TEXT_TRUNCATED_NOTICE)
                    }

                assertEquals(CHAT_STREAMING_ASSISTANT_TEXT_MAX_CHARS, streaming.streamingAssistantText.length)

                viewModel.cancelActiveTurn()
                awaitState { state -> !state.isRunning && state.streamingAssistantText.isEmpty() }

                val stored = messageRepository.observeMessages(ready.currentSessionId).first()
                assertTrue(stored.none { it.role == MessageRole.Assistant })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `switching sessions changes the observed message list`() =
        runTest {
            val mainSession = sessionRepository.getOrCreateMainSession()
            messageRepository.addMessage(mainSession.id, MessageRole.Assistant, "main message")
            val otherSession = sessionRepository.createSession("Project X")
            messageRepository.addMessage(otherSession.id, MessageRole.Assistant, "other message")

            viewModel.state.test {
                awaitState { it.currentSessionId == mainSession.id && it.messages.any { message -> message.text == "main message" } }

                viewModel.switchSession(otherSession.id)

                val switched =
                    awaitState { state ->
                        state.currentSessionId == otherSession.id && state.messages.any { it.text == "other message" }
                    }

                assertEquals("Project X", switched.sessionTitle)
                assertEquals(listOf("other message"), switched.messages.map { it.text })
            }
        }

    @Test
    fun `session usage summary aggregates assistant provider metadata`() =
        runTest {
            val mainSession = sessionRepository.getOrCreateMainSession()
            messageRepository.addMessage(
                sessionId = mainSession.id,
                role = MessageRole.Assistant,
                content = "first",
                providerMeta =
                    ProviderMessageMeta(
                        providerId = "openai-compatible",
                        requestId = "req-1",
                        modelId = "gpt-test",
                        usage =
                            ProviderUsagePayload(
                                inputTokens = 10,
                                outputTokens = 4,
                                totalTokens = 14,
                            ),
                    ).toStorageString(),
            )
            messageRepository.addMessage(
                sessionId = mainSession.id,
                role = MessageRole.Assistant,
                content = "second",
                providerMeta =
                    ProviderMessageMeta(
                        providerId = "anthropic",
                        requestId = "req-2",
                        modelId = "claude-test",
                        usage =
                            ProviderUsagePayload(
                                inputTokens = 6,
                                outputTokens = 3,
                                totalTokens = 9,
                            ),
                    ).toStorageString(),
            )

            viewModel.state.test {
                val state =
                    awaitState {
                        it.currentSessionId == mainSession.id &&
                            it.sessionUsageSummary?.contains("2 tracked replies") == true
                    }

                assertEquals("2 tracked replies · 16 input · 7 output · 23 total tokens", state.sessionUsageSummary)
            }
        }

    @Test
    fun `creating a new session adds it to the session list and selects it`() =
        runTest {
            viewModel.state.test {
                val ready = awaitState { it.currentSessionId.isNotBlank() && it.sessions.isNotEmpty() }

                viewModel.createNewSession("Project Y")

                val created =
                    awaitState { state ->
                        state.sessions.size == ready.sessions.size + 1 && state.sessions.any { it.title == "Project Y" && it.isSelected }
                    }

                assertEquals("Project Y", created.sessionTitle)
                assertTrue(created.currentSessionId.isNotBlank())
            }
        }

    @Test
    fun `stored session summary is surfaced in chat state`() =
        runTest {
            viewModel.state.test {
                val ready = awaitState { it.currentSessionId.isNotBlank() && it.sessions.isNotEmpty() }

                sessionRepository.updateSummary(ready.currentSessionId, "Summary preview for the current session.")

                val withSummary = awaitState { it.sessionSummary == "Summary preview for the current session." }

                assertEquals("Summary preview for the current session.", withSummary.sessionSummary)
            }
        }

    @Test
    fun `compacted session hides older messages until user reveals durable history`() =
        runTest {
            val mainSession = sessionRepository.getOrCreateMainSession()
            val oldUser = messageRepository.addMessage(mainSession.id, MessageRole.User, "old setup")
            val boundary = messageRepository.addMessage(mainSession.id, MessageRole.Assistant, "old answer")
            messageRepository.addMessage(mainSession.id, MessageRole.User, "new question")
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = mainSession.id,
                summaryText = "Old setup was answered.",
                compactedUntilMessageId = boundary.id,
            )

            viewModel.state.test {
                val compacted =
                    awaitState {
                        it.currentSessionId == mainSession.id &&
                            it.compactedHiddenMessageCount == 2 &&
                            it.messages.map { message -> message.text } == listOf("new question")
                    }

                assertEquals("Old setup was answered.", compacted.sessionSummary)
                assertTrue(compacted.canCompactCurrentSession)
                assertTrue(!compacted.showCompactedHistory)
                assertTrue(compacted.messages.none { message -> message.id == oldUser.id })
                assertTrue(compacted.messages.none { message -> message.id == boundary.id })

                viewModel.toggleCompactedHistory()

                val revealed =
                    awaitState {
                        it.showCompactedHistory &&
                            it.messages.map { message -> message.text } == listOf("old setup", "old answer", "new question")
                    }

                assertTrue(revealed.messages.any { message -> message.id == oldUser.id })
                assertTrue(revealed.messages.any { message -> message.id == boundary.id })

                viewModel.toggleCompactedHistory()

                val hiddenAgain =
                    awaitState {
                        !it.showCompactedHistory &&
                            it.messages.map { message -> message.text } == listOf("new question")
                    }

                assertTrue(hiddenAgain.messages.none { message -> message.id == oldUser.id })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `compacted session hides compact command plumbing by default`() =
        runTest {
            val mainSession = sessionRepository.getOrCreateMainSession()
            messageRepository.addMessage(mainSession.id, MessageRole.User, "old setup should be hidden")
            val compactToolCall =
                messageRepository.addMessage(
                    mainSession.id,
                    MessageRole.ToolCall,
                    "Tool request: sessions.compact {summary stored in session summary}",
                )
            messageRepository.addMessage(
                mainSession.id,
                MessageRole.ToolResult,
                "Tool result: Compacted this session summary and hid older messages.",
            )
            messageRepository.addMessage(
                mainSession.id,
                MessageRole.Assistant,
                "Compacted this session summary and hid older messages.\n\nActive skills: compact",
            )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = mainSession.id,
                summaryText = "Old setup was answered.",
                compactedUntilMessageId = compactToolCall.id,
            )

            viewModel.state.test {
                val compacted =
                    awaitState {
                        it.currentSessionId == mainSession.id &&
                            it.compactedHiddenMessageCount == 2 &&
                            it.messages.isEmpty()
                    }

                assertEquals("Old setup was answered.", compacted.sessionSummary)
                assertTrue(!compacted.canCompactCurrentSession)
                assertTrue(compacted.messages.none { message -> message.text.contains("old setup should be hidden") })
                assertTrue(compacted.messages.none { message -> message.text.contains("sessions.compact") })

                viewModel.toggleCompactedHistory()

                val revealed =
                    awaitState {
                        it.showCompactedHistory &&
                            it.messages.map { message -> message.text } == listOf("old setup should be hidden")
                    }

                assertTrue(revealed.messages.none { message -> message.text.contains("sessions.compact") })
                assertTrue(revealed.messages.none { message -> message.text.contains("Compacted this session") })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `search opens matching session message and highlights it`() =
        runTest {
            val otherSession = sessionRepository.createSession("Project Alpha")
            messageRepository.addMessage(
                sessionId = otherSession.id,
                role = MessageRole.Assistant,
                content = "Alpha task is scheduled for tomorrow.",
            )

            viewModel.state.test {
                awaitState { it.currentSessionId.isNotBlank() && it.sessions.isNotEmpty() }

                viewModel.onSearchQueryChanged("Alpha")
                viewModel.runSearch()

                val searchReady = awaitState { it.searchResults.isNotEmpty() }
                val messageHit = searchReady.searchResults.first { it.messageId != null }

                viewModel.openSearchResult(messageHit)

                val opened =
                    awaitState {
                        it.currentSessionId == otherSession.id &&
                            it.highlightedMessageId == messageHit.messageId &&
                            it.messages.any { message -> message.id == messageHit.messageId }
                    }

                assertEquals(otherSession.id, opened.currentSessionId)
                assertEquals(messageHit.messageId, opened.highlightedMessageId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `search opens compacted hidden message by revealing durable history`() =
        runTest {
            val mainSession = sessionRepository.getOrCreateMainSession()
            val oldUser = messageRepository.addMessage(mainSession.id, MessageRole.User, "buried setup detail")
            val boundary = messageRepository.addMessage(mainSession.id, MessageRole.Assistant, "old answer")
            messageRepository.addMessage(mainSession.id, MessageRole.User, "new question")
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = mainSession.id,
                summaryText = "Old setup was answered.",
                compactedUntilMessageId = boundary.id,
            )

            viewModel.state.test {
                awaitState {
                    it.currentSessionId == mainSession.id &&
                        it.compactedHiddenMessageCount == 2 &&
                        it.messages.none { message -> message.id == oldUser.id }
                }

                viewModel.onSearchQueryChanged("buried")
                viewModel.runSearch()

                val searchReady = awaitState { it.searchResults.any { result -> result.messageId == oldUser.id } }
                val messageHit = searchReady.searchResults.first { it.messageId == oldUser.id }

                viewModel.openSearchResult(messageHit)

                val opened =
                    awaitState {
                        it.currentSessionId == mainSession.id &&
                            it.showCompactedHistory &&
                            it.highlightedMessageId == oldUser.id &&
                            it.messages.any { message -> message.id == oldUser.id }
                    }

                assertEquals(oldUser.id, opened.highlightedMessageId)
                assertTrue(opened.messages.any { message -> message.text == "buried setup detail" })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `export current session emits markdown document payload`() =
        runTest {
            val mainSession = sessionRepository.getOrCreateMainSession()
            messageRepository.addMessage(
                sessionId = mainSession.id,
                role = MessageRole.Assistant,
                content = "Export this session.",
            )

            viewModel.state.test {
                awaitState { it.currentSessionId == mainSession.id && it.sessions.isNotEmpty() }
                val actionDeferred = backgroundScope.async { viewModel.actions.first() }

                viewModel.exportCurrentSession(ChatExportFormat.Markdown)

                val action = actionDeferred.await()
                val exported = awaitState { it.noticeMessage?.contains("Ready to save") == true }
                val payload = (action as ChatExternalAction.ExportDocument).payload

                assertTrue(payload.fileName.endsWith(".md"))
                assertTrue(payload.content.contains("Export this session."))
                assertTrue(payload.content.contains("## Transcript"))
                assertEquals(mainSession.id, exported.currentSessionId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `share current session as file emits share file payload`() =
        runTest {
            val mainSession = sessionRepository.getOrCreateMainSession()
            messageRepository.addMessage(
                sessionId = mainSession.id,
                role = MessageRole.Assistant,
                content = "Share this session.",
            )

            viewModel.state.test {
                awaitState { it.currentSessionId == mainSession.id && it.sessions.isNotEmpty() }
                val actionDeferred = backgroundScope.async { viewModel.actions.first() }

                viewModel.shareCurrentSessionAsFile(ChatExportFormat.Json)

                val action = actionDeferred.await()
                val payload = (action as ChatExternalAction.ShareFile).payload

                assertTrue(payload.fileName.endsWith(".json"))
                assertTrue(payload.content.contains("\"messages\""))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `share current session as text emits bounded text payload`() =
        runTest {
            val mainSession = sessionRepository.getOrCreateMainSession()
            repeat(3) { index ->
                messageRepository.addMessage(
                    sessionId = mainSession.id,
                    role = MessageRole.Assistant,
                    content = "share-$index-" + "x".repeat(45_000),
                )
            }

            viewModel.state.test {
                awaitState { it.currentSessionId == mainSession.id && it.sessions.isNotEmpty() }
                val actionDeferred = backgroundScope.async { viewModel.actions.first() }

                viewModel.shareCurrentSessionAsText()

                val action = actionDeferred.await()
                val shared = action as ChatExternalAction.ShareText
                val sharing = awaitState { it.noticeMessage == "Opening share sheet." }

                assertEquals(mainSession.id, sharing.currentSessionId)
                assertTrue(shared.text.length <= CHAT_SHARE_TEXT_MAX_CHARS)
                assertTrue(shared.text.endsWith(CHAT_SHARE_TEXT_TRUNCATED_NOTICE))
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun buildViewModel(
        providerRegistry: ai.androidclaw.runtime.providers.ProviderRegistry = buildTestProviderRegistry(),
        toolRegistry: ToolRegistry = ToolRegistry(emptyList()),
        skillManager: SkillManager = this.skillManager,
    ): ChatViewModel =
        ChatViewModel(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            eventLogRepository = eventLogRepository,
            agentRunner =
                AgentRunner(
                    providerRegistry = providerRegistry,
                    settingsDataStore = settingsDataStore,
                    messageRepository = messageRepository,
                    skillManager = skillManager,
                    toolRegistry = toolRegistry,
                    sessionLaneCoordinator = SessionLaneCoordinator(),
                    promptAssembler = PromptAssembler(),
                    loadSessionSummary = { id -> sessionRepository.getSession(id)?.summaryText },
                    loadSessionCompactionBoundary = { id -> sessionRepository.getSession(id)?.compactedUntilMessageId },
                ),
            skillManager = skillManager,
            settingsDataStore = settingsDataStore,
        )
}

private fun buildSkillManager(
    application: android.app.Application,
    toolRegistry: ToolRegistry,
    skillRepository: ai.androidclaw.data.repository.SkillRepository,
): SkillManager =
    createTestSkillManager(
        application = application,
        skillRepository = skillRepository,
        toolDescriptor = toolRegistry::findDescriptor,
    )

private suspend fun ReceiveTurbine<ChatUiState>.awaitState(
    predicate: (ChatUiState) -> Boolean,
): ChatUiState =
    withTurbineTimeout(20.seconds) {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) {
                return@withTurbineTimeout item
            }
        }
        error("unreachable")
    }
