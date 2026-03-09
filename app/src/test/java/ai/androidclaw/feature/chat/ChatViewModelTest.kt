package ai.androidclaw.feature.chat

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelProviderException
import ai.androidclaw.runtime.providers.ModelProviderFailureKind
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.skills.BundledSkillLoader
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillParser
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.testutil.buildTestProviderRegistry
import ai.androidclaw.testutil.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
    fun setUp() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        database = buildTestDatabase(application)
        sessionRepository = SessionRepository(database.sessionDao())
        messageRepository = MessageRepository(database.messageDao())
        eventLogRepository = EventLogRepository(database.eventLogDao())
        settingsDataStore = SettingsDataStore(application)
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())

        val toolRegistry = ToolRegistry(emptyList())
        skillManager = buildSkillManager(application, toolRegistry)
        viewModel = buildViewModel(
            toolRegistry = toolRegistry,
            skillManager = skillManager,
        )
    }

    @After
    fun tearDown() = runTest {
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        database.close()
    }

    @Test
    fun `sending a message persists both user and assistant messages`() = runTest {
        viewModel.state.test {
            val ready = awaitState { it.currentSessionId.isNotBlank() && it.sessions.isNotEmpty() }

            viewModel.onDraftChanged("hello")
            viewModel.sendCurrentDraft()

            val completed = awaitState { state ->
                !state.isRunning && state.messages.any { it.role == "assistant" && it.text.contains("Reply: hello") }
            }

            val stored = messageRepository.observeMessages(ready.currentSessionId).first()
            assertEquals(ready.currentSessionId, completed.currentSessionId)
            assertTrue(stored.any { it.role == MessageRole.User && it.content == "hello" })
            assertTrue(stored.any { it.role == MessageRole.Assistant && it.content.contains("Reply: hello") })
        }
    }

    @Test
    fun `switching sessions changes the observed message list`() = runTest {
        val mainSession = sessionRepository.getOrCreateMainSession()
        messageRepository.addMessage(mainSession.id, MessageRole.Assistant, "main message")
        val otherSession = sessionRepository.createSession("Project X")
        messageRepository.addMessage(otherSession.id, MessageRole.Assistant, "other message")

        viewModel.state.test {
            awaitState { it.currentSessionId == mainSession.id && it.messages.any { message -> message.text == "main message" } }

            viewModel.switchSession(otherSession.id)

            val switched = awaitState { state ->
                state.currentSessionId == otherSession.id && state.messages.any { it.text == "other message" }
            }

            assertEquals("Project X", switched.sessionTitle)
            assertEquals(listOf("other message"), switched.messages.map { it.text })
        }
    }

    @Test
    fun `creating a new session adds it to the session list and selects it`() = runTest {
        viewModel.state.test {
            val ready = awaitState { it.currentSessionId.isNotBlank() && it.sessions.isNotEmpty() }

            viewModel.createNewSession("Project Y")

            val created = awaitState { state ->
                state.sessions.size == ready.sessions.size + 1 && state.sessions.any { it.title == "Project Y" && it.isSelected }
            }

            assertEquals("Project Y", created.sessionTitle)
            assertTrue(created.currentSessionId.isNotBlank())
        }
    }

    @Test
    fun `provider failures do not leave chat stuck and persist a system error message`() = runTest {
        viewModel = buildViewModel(
            providerRegistry = buildTestProviderRegistry(
                fakeProvider = object : ModelProvider {
                    override val id: String = "fake"

                    override suspend fun generate(request: ModelRequest): ModelResponse {
                        throw ModelProviderException(
                            kind = ModelProviderFailureKind.Network,
                            userMessage = "Provider unavailable",
                        )
                    }
                },
            ),
        )

        viewModel.state.test {
            val ready = awaitState { it.currentSessionId.isNotBlank() && it.sessions.isNotEmpty() }

            viewModel.onDraftChanged("hello")
            viewModel.sendCurrentDraft()

            val failed = awaitState { state ->
                !state.isRunning &&
                    state.errorMessage == "Provider unavailable" &&
                    state.messages.any { it.role == "system" && it.text.contains("Turn failed") }
            }

            val stored = messageRepository.observeMessages(ready.currentSessionId).first()
            val events = eventLogRepository.observeRecent(limit = 10).first { it.isNotEmpty() }
            assertEquals("Provider unavailable", failed.errorMessage)
            assertTrue(stored.any { it.role == MessageRole.User && it.content == "hello" })
            assertTrue(stored.any { it.role == MessageRole.System && it.content.contains("Provider unavailable") })
            assertTrue(events.any { it.category == EventCategory.Provider && it.message == "Provider unavailable" })
        }
    }

    private fun buildViewModel(
        providerRegistry: ai.androidclaw.runtime.providers.ProviderRegistry = buildTestProviderRegistry(),
        toolRegistry: ToolRegistry = ToolRegistry(emptyList()),
        skillManager: SkillManager = this.skillManager,
    ): ChatViewModel {
        return ChatViewModel(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            eventLogRepository = eventLogRepository,
            agentRunner = AgentRunner(
                providerRegistry = providerRegistry,
                settingsDataStore = settingsDataStore,
                messageRepository = messageRepository,
                skillManager = skillManager,
                toolRegistry = toolRegistry,
            ),
            skillManager = skillManager,
        )
    }
}

private fun buildSkillManager(
    application: android.app.Application,
    toolRegistry: ToolRegistry,
): SkillManager {
    return SkillManager(
        bundledSkillLoader = BundledSkillLoader(
            assetManager = application.assets,
            rootPath = "skills",
            parser = SkillParser(),
        ),
        toolDescriptor = toolRegistry::findDescriptor,
    )
}

private suspend fun ReceiveTurbine<ChatUiState>.awaitState(
    predicate: (ChatUiState) -> Boolean,
): ChatUiState {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) {
            return item
        }
    }
}
