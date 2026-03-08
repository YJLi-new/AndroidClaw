package ai.androidclaw.feature.chat

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.providers.FakeProvider
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.skills.BundledSkillLoader
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillParser
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.testutil.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
    private lateinit var skillManager: SkillManager
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        database = buildTestDatabase(application)
        sessionRepository = SessionRepository(database.sessionDao())
        messageRepository = MessageRepository(database.messageDao())

        val toolRegistry = ToolRegistry(emptyList())
        skillManager = SkillManager(
            bundledSkillLoader = BundledSkillLoader(
                assetManager = application.assets,
                rootPath = "skills",
                parser = SkillParser(),
            ),
            toolExists = { true },
        )
        viewModel = ChatViewModel(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            agentRunner = AgentRunner(
                providerRegistry = ProviderRegistry(
                    defaultProvider = FakeProvider(
                        clock = Clock.fixed(
                            Instant.parse("2026-03-08T00:00:00Z"),
                            ZoneOffset.UTC,
                        ),
                    ),
                ),
                skillManager = skillManager,
                toolRegistry = toolRegistry,
            ),
            skillManager = skillManager,
        )
    }

    @After
    fun tearDown() {
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
        viewModel = ChatViewModel(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            agentRunner = AgentRunner(
                providerRegistry = ProviderRegistry(
                    defaultProvider = object : ModelProvider {
                        override val id: String = "failing"

                        override suspend fun generate(request: ModelRequest): ModelResponse {
                            throw IllegalStateException("Provider unavailable")
                        }
                    },
                ),
                skillManager = skillManager,
                toolRegistry = ToolRegistry(emptyList()),
            ),
            skillManager = skillManager,
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
            assertEquals("Provider unavailable", failed.errorMessage)
            assertTrue(stored.any { it.role == MessageRole.User && it.content == "hello" })
            assertTrue(stored.any { it.role == MessageRole.System && it.content.contains("Provider unavailable") })
        }
    }
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
