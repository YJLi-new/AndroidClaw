package ai.androidclaw.runtime.scheduler

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.dao.MessageDao
import ai.androidclaw.data.db.dao.MessageSearchRow
import ai.androidclaw.data.db.entity.MessageEntity
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.orchestrator.PromptAssembler
import ai.androidclaw.runtime.orchestrator.SessionLaneCoordinator
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.createTestSkillManager
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskRuntimeExecutorTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var sessionRepository: SessionRepository
    private lateinit var taskRepository: TaskRepository

    @Before
    fun setUp() = runTest {
        application = ApplicationProvider.getApplicationContext()
        database = buildTestDatabase(application)
        sessionRepository = SessionRepository(database.sessionDao())
        taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `isolated execution reports explicit failure when delivery write fails`() = runTest {
        val targetSession = sessionRepository.createSession("Delivery target")
        val throwingMessages = MessageRepository(
            ThrowingMessageDao(
                delegate = database.messageDao(),
                failingSessionId = targetSession.id,
            ),
        )
        val executor = TaskRuntimeExecutor(
            sessionRepository = sessionRepository,
            messageRepository = throwingMessages,
            agentRunner = buildAgentRunner(throwingMessages),
            sessionLaneCoordinator = SessionLaneCoordinator(),
        )
        val task = taskRepository.createTask(
            name = "Isolated analysis",
            prompt = "Analyze privately",
            schedule = TaskSchedule.Once(Instant.parse("2026-03-09T00:00:00Z")),
            executionMode = TaskExecutionMode.IsolatedSession,
            targetSessionId = targetSession.id,
        )

        val execution = executor.execute(
            task = task,
            taskRunId = "run-1",
        )
        val sessions = sessionRepository.observeSessions().first()
        val isolatedSession = sessions.firstOrNull { it.id != targetSession.id }
        val targetMessages = MessageRepository(database.messageDao()).getRecentMessages(targetSession.id, limit = 10)
        val isolatedMessages = isolatedSession?.let {
            MessageRepository(database.messageDao()).getRecentMessages(it.id, limit = 10)
        }.orEmpty()

        assertFalse(execution.success)
        assertEquals("TASK_DELIVERY_FAILED", execution.errorCode)
        assertEquals(null, execution.outputMessageId)
        assertEquals(false, execution.retryable)
        assertTrue(execution.summary.contains("completed in isolated session"))
        assertNotNull(isolatedSession)
        assertTrue(isolatedMessages.any { it.role == MessageRole.Assistant })
        assertTrue(targetMessages.isEmpty())
    }

    private suspend fun buildAgentRunner(messageRepository: MessageRepository): AgentRunner {
        val settingsDataStore = ai.androidclaw.data.SettingsDataStore(application)
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        return AgentRunner(
            providerRegistry = buildTestProviderRegistry(
                fakeProvider = object : ModelProvider {
                    override val id: String = "fake"

                    override suspend fun generate(request: ModelRequest): ModelResponse {
                        return ModelResponse(text = "Isolated execution complete.")
                    }
                },
            ),
            settingsDataStore = settingsDataStore,
            messageRepository = messageRepository,
            skillManager = createTestSkillManager(
                application = application,
                skillRepository = SkillRepository(database.skillRecordDao()),
                toolDescriptor = ToolRegistry(emptyList())::findDescriptor,
            ),
            toolRegistry = ToolRegistry(emptyList()),
            sessionLaneCoordinator = SessionLaneCoordinator(),
            promptAssembler = PromptAssembler(),
        )
    }
}

private class ThrowingMessageDao(
    private val delegate: MessageDao,
    private val failingSessionId: String,
) : MessageDao {
    override suspend fun insert(message: MessageEntity) {
        if (message.sessionId == failingSessionId) {
            throw IllegalStateException("Delivery insert failed.")
        }
        delegate.insert(message)
    }

    override suspend fun insertAll(messages: List<MessageEntity>) {
        for (message in messages) {
            insert(message)
        }
    }

    override fun getBySessionId(sessionId: String): Flow<List<MessageEntity>> = delegate.getBySessionId(sessionId)

    override suspend fun getAllBySessionId(sessionId: String): List<MessageEntity> {
        return delegate.getAllBySessionId(sessionId)
    }

    override suspend fun getRecentBySessionId(sessionId: String, limit: Int): List<MessageEntity> {
        return delegate.getRecentBySessionId(sessionId, limit)
    }

    override suspend fun getByIds(messageIds: List<String>): List<MessageEntity> {
        return delegate.getByIds(messageIds)
    }

    override suspend fun countBySessionId(sessionId: String): Int = delegate.countBySessionId(sessionId)

    override suspend fun searchByContent(query: String, limit: Int): List<MessageSearchRow> {
        return delegate.searchByContent(query, limit)
    }

    override suspend fun deleteBySessionId(sessionId: String) {
        delegate.deleteBySessionId(sessionId)
    }
}
