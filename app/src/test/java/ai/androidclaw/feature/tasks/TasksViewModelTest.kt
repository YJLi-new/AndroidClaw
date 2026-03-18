package ai.androidclaw.feature.tasks

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.testutil.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TasksViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: TaskRepository
    private lateinit var eventLogRepository: EventLogRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var viewModel: TasksViewModel

    @Before
    fun setUp() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            application,
            Configuration.Builder().build(),
        )
        database = buildTestDatabase(application)
        database.sessionDao().insert(
            SessionEntity(
                id = "main",
                title = "Main session",
                isMain = true,
                createdAt = 1L,
                updatedAt = 1L,
                archivedAt = null,
                summaryText = null,
            ),
        )
        repository = TaskRepository(database.taskDao(), database.taskRunDao())
        eventLogRepository = EventLogRepository(database.eventLogDao())
        messageRepository = MessageRepository(database.messageDao())
        viewModel = TasksViewModel(
            taskRepository = repository,
            schedulerCoordinator = SchedulerCoordinator(
                application = application,
                clock = Clock.fixed(Instant.parse("2026-03-08T00:00:00Z"), ZoneOffset.UTC),
                taskRepository = repository,
                eventLogRepository = eventLogRepository,
            ),
            sessionRepository = SessionRepository(database.sessionDao()),
            messageRepository = messageRepository,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `create and toggle task update state`() = runTest {
        viewModel.state.test {
            awaitState { it.tasks.isEmpty() }

            viewModel.createTask(
                name = "Daily check",
                prompt = "Check status",
                schedule = TaskSchedule.Once(Instant.parse("2026-03-09T00:00:00Z")),
                executionMode = TaskExecutionMode.MainSession,
                targetSessionId = "main",
            )

            val created = awaitState { it.tasks.size == 1 }
            assertEquals("Daily check", created.tasks.single().name)

            viewModel.toggleEnabled(created.tasks.single().id)
            val toggled = awaitState { state -> state.tasks.size == 1 && !state.tasks.single().enabled }
            assertFalse(toggled.tasks.single().enabled)
        }
    }

    @Test
    fun `runNow queues execution and reports action message`() = runTest {
        viewModel.state.test {
            viewModel.createTask(
                name = "Manual check",
                prompt = "Check now",
                schedule = TaskSchedule.Once(Instant.parse("2026-03-09T00:00:00Z")),
                executionMode = TaskExecutionMode.MainSession,
                targetSessionId = "main",
            )

            val created = awaitState { it.tasks.size == 1 }
            viewModel.clearActionMessage()
            viewModel.runNow(created.tasks.single().id)

            val updated = awaitState { it.actionMessage == "Queued run now for Manual check." }
            assertEquals(
                "Queued run now for Manual check.",
                updated.actionMessage,
            )
        }
    }

    @Test
    fun `recent task runs surface provider usage from output messages`() = runTest {
        viewModel.state.test {
            viewModel.createTask(
                name = "Usage check",
                prompt = "Count tokens",
                schedule = TaskSchedule.Once(Instant.parse("2026-03-09T00:00:00Z")),
                executionMode = TaskExecutionMode.MainSession,
                targetSessionId = "main",
            )

            val created = awaitState { it.tasks.size == 1 }
            val task = created.tasks.single()
            val assistantMessage = messageRepository.addMessage(
                sessionId = "main",
                role = ai.androidclaw.data.model.MessageRole.Assistant,
                content = "Done.",
                providerMeta = """
                    {"providerId":"anthropic","modelId":"claude-3-7-sonnet","usage":{"inputTokens":150,"outputTokens":75,"totalTokens":225}}
                """.trimIndent(),
            )
            val run = repository.recordRun(
                taskId = task.id,
                scheduledAt = Instant.parse("2026-03-09T00:00:00Z"),
            )
            repository.updateRun(
                run.copy(
                    status = ai.androidclaw.data.model.TaskRunStatus.Success,
                    startedAt = Instant.parse("2026-03-09T00:00:00Z"),
                    finishedAt = Instant.parse("2026-03-09T00:00:05Z"),
                    resultSummary = "Completed.",
                    outputMessageId = assistantMessage.id,
                ),
            )

            val withUsage = awaitState {
                it.runUsageSummaryByRunId[run.id] == "anthropic · claude-3-7-sonnet · total 225 tokens"
            }
            assertEquals(
                "anthropic · claude-3-7-sonnet · total 225 tokens",
                withUsage.runUsageSummaryByRunId[run.id],
            )
        }
    }
}

private suspend fun ReceiveTurbine<TasksUiState>.awaitState(
    predicate: (TasksUiState) -> Boolean,
): TasksUiState {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) {
            return item
        }
    }
}
