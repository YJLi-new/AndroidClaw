package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SessionSummaryCoordinatorTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var sessionRepository: SessionRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() = runTest {
        application = ApplicationProvider.getApplicationContext()
        database = buildTestDatabase(application)
        sessionRepository = SessionRepository(database.sessionDao())
        messageRepository = MessageRepository(database.messageDao())
        settingsDataStore = SettingsDataStore(application)
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot(providerType = ProviderType.OpenAiCompatible),
        )
    }

    @After
    fun tearDown() = runTest {
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        database.close()
    }

    @Test
    fun `eligible session generates and stores a summary`() = runTest {
        val provider = RecordingSummaryProvider("First summary")
        val coordinator = buildCoordinator(provider = provider)
        val sessionId = seedConversation(messageCount = 4)

        val result = coordinator.maybeRefreshSummary(sessionId)

        assertTrue(result.refreshed)
        assertEquals("First summary", sessionRepository.getSession(sessionId)?.summaryText)
        assertEquals(1, provider.requests.size)
        assertTrue(provider.requests.single().messageHistory.single().content.contains("Recent transcript"))
    }

    @Test
    fun `summary refresh waits for enough new messages`() = runTest {
        val provider = RecordingSummaryProvider("First summary", "Updated summary")
        val coordinator = buildCoordinator(provider = provider)
        val sessionId = seedConversation(messageCount = 4)

        val initial = coordinator.maybeRefreshSummary(sessionId)
        assertTrue(initial.refreshed)

        appendConversationMessages(sessionId, 1)
        val skipped = coordinator.maybeRefreshSummary(sessionId)
        assertFalse(skipped.refreshed)
        assertEquals("not_enough_new_messages", skipped.skippedReason)

        appendConversationMessages(sessionId, 1)
        val refreshed = coordinator.maybeRefreshSummary(sessionId)
        assertTrue(refreshed.refreshed)
        assertEquals("Updated summary", sessionRepository.getSession(sessionId)?.summaryText)
        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests.last().messageHistory.single().content.contains("Existing summary:"))
    }

    @Test
    fun `fake provider is skipped by default`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot(providerType = ProviderType.Fake),
        )
        val provider = RecordingSummaryProvider("Should not be used")
        val coordinator = buildCoordinator(provider = provider)
        val sessionId = seedConversation(messageCount = 4)

        val result = coordinator.maybeRefreshSummary(sessionId)

        assertFalse(result.refreshed)
        assertEquals("fake_provider_disabled", result.skippedReason)
        assertTrue(sessionRepository.getSession(sessionId)?.summaryText.isNullOrBlank())
        assertTrue(provider.requests.isEmpty())
    }

    private fun TestScope.buildCoordinator(
        provider: ModelProvider,
    ): SessionSummaryCoordinator {
        return SessionSummaryCoordinator(
            applicationScope = backgroundScope,
            providerRegistry = buildTestProviderRegistry(openAiCompatibleProvider = provider),
            settingsDataStore = settingsDataStore,
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            sessionLaneCoordinator = SessionLaneCoordinator(),
            minMessageCount = 4,
            refreshIntervalMessages = 2,
            summarySourceMessageLimit = 12,
        )
    }

    private suspend fun seedConversation(messageCount: Int): String {
        val sessionId = sessionRepository.createSession("Summary test").id
        appendConversationMessages(sessionId, messageCount)
        return sessionId
    }

    private suspend fun appendConversationMessages(sessionId: String, messageCount: Int) {
        repeat(messageCount) { index ->
            messageRepository.addMessage(
                sessionId = sessionId,
                role = if (index % 2 == 0) MessageRole.User else MessageRole.Assistant,
                content = "message-${messageRepository.getMessageCount(sessionId)}",
            )
        }
    }

    private class RecordingSummaryProvider(
        vararg summaries: String,
    ) : ModelProvider {
        override val id: String = "summary-test"
        private val queuedSummaries = ArrayDeque(summaries.toList())
        val requests = mutableListOf<ModelRequest>()

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += request
            val summary = queuedSummaries.removeFirstOrNull() ?: "Fallback summary"
            return ModelResponse(text = summary)
        }
    }
}
