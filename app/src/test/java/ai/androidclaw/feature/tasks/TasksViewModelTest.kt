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
import ai.androidclaw.testutil.cancelAndJoinScope
import ai.androidclaw.testutil.textOrNull
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

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
    fun setUp() =
        runTest {
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
            viewModel =
                TasksViewModel(
                    taskRepository = repository,
                    schedulerCoordinator =
                        SchedulerCoordinator(
                            application = application,
                            clock = Clock.fixed(Instant.parse("2026-03-08T00:00:00Z"), ZoneOffset.UTC),
                            taskRepository = repository,
                            eventLogRepository = eventLogRepository,
                        ),
                    sessionRepository = SessionRepository(database.sessionDao()),
                    messageRepository = messageRepository,
                    zoneIdProvider = { ZoneOffset.UTC },
                    clock = { Instant.parse("2026-03-08T00:00:00Z") },
                )
        }

    @After
    fun tearDown() =
        runTest {
            // Stop the ViewModel built here before Main is reset for the next test.
            viewModel.cancelAndJoinScope()
            database.close()
        }

    @Test
    fun `create and toggle task update state`() =
        runTest {
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
                // toggleEnabled also posts an action message; it may arrive after the awaited state.
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `runNow queues execution and reports action message`() =
        runTest {
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

                val updated = awaitState { it.actionMessage.textOrNull() == "Queued run now for Manual check." }
                assertEquals(
                    "Queued run now for Manual check.",
                    updated.actionMessage.textOrNull(),
                )
            }
        }

    @Test
    fun `saveTask reports validation errors without creating a task`() =
        runTest {
            viewModel.state.test {
                awaitState { it.loaded }
                var saved = false

                val missingName = viewModel.saveTask(testForm(name = "  "), existingTaskId = null) { saved = true }
                val badInterval =
                    viewModel.saveTask(
                        testForm(schedule = intervalDraft("5")),
                        existingTaskId = null,
                    ) { saved = true }

                assertEquals("Enter a task name.", missingName.textOrNull())
                assertEquals("Intervals must be at least 15 minutes.", badInterval.textOrNull())
                assertFalse(saved)
                assertTrue(repository.observeTasks().first().isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `saveTask edits an existing task and resets its schedule`() =
        runTest {
            viewModel.state.test {
                viewModel.createTask(
                    name = "Morning brief",
                    prompt = "Summarize",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-09T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
                val created = awaitState { it.tasks.size == 1 }
                val taskId = created.tasks.single().id
                var saved = false

                val error =
                    viewModel.saveTask(
                        testForm(
                            name = "Hourly brief",
                            prompt = "Summarize again",
                            schedule = intervalDraft("60"),
                            executionMode = TaskExecutionMode.IsolatedSession,
                        ),
                        existingTaskId = taskId,
                    ) { saved = true }
                assertNull(error)

                val edited =
                    awaitState { state ->
                        state.tasks.singleOrNull()?.name == "Hourly brief" &&
                            state.actionMessage.textOrNull() == "Saved task Hourly brief."
                    }
                val task = edited.tasks.single()
                assertEquals(taskId, task.id)
                assertEquals("Summarize again", task.prompt)
                assertEquals(TaskExecutionMode.IsolatedSession, task.executionMode)
                assertNull(task.targetSessionId)
                assertEquals(TaskSchedule.Interval(anchorAt = Instant.parse("2026-03-08T00:00:00Z"), repeatEvery = Duration.ofMinutes(60)), task.schedule)
                assertTrue(task.nextRunAt!!.isAfter(Instant.parse("2026-03-08T00:00:00Z")))
                assertTrue(saved)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `re-enabling a task with a stale wake time schedules a fresh one`() =
        runTest {
            viewModel.state.test {
                viewModel.createTask(
                    name = "Repeat",
                    prompt = "Ping",
                    schedule = TaskSchedule.Interval(anchorAt = Instant.parse("2026-03-01T00:00:00Z"), repeatEvery = Duration.ofHours(1)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
                val created = awaitState { it.tasks.size == 1 }
                val task = created.tasks.single()
                viewModel.setEnabled(task.id, enabled = false)
                awaitState { state ->
                    state.tasks
                        .single()
                        .enabled
                        .not()
                }
                repository.updateTask(repository.getTask(task.id)!!.copy(nextRunAt = Instant.parse("2026-03-02T00:00:00Z")))

                viewModel.setEnabled(task.id, enabled = true)
                val enabled = awaitState { state -> state.tasks.single().enabled && state.actionMessage.textOrNull() == "Enabled Repeat." }

                assertTrue(
                    enabled.tasks
                        .single()
                        .nextRunAt!!
                        .isAfter(Instant.parse("2026-03-08T00:00:00Z")),
                )
                assertEquals("Enabled Repeat.", enabled.actionMessage.textOrNull())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `recent task runs surface provider usage from output messages`() =
        runTest {
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
                val assistantMessage =
                    messageRepository.addMessage(
                        sessionId = "main",
                        role = ai.androidclaw.data.model.MessageRole.Assistant,
                        content = "Done.",
                        providerMeta =
                            """
                            {"providerId":"anthropic","modelId":"claude-3-7-sonnet","usage":{"inputTokens":150,"outputTokens":75,"totalTokens":225}}
                            """.trimIndent(),
                    )
                val run =
                    repository.recordRun(
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

                val withUsage =
                    awaitState {
                        it.runUsageSummaryByRunId[run.id].textOrNull() == "anthropic · claude-3-7-sonnet · total 225 tokens"
                    }
                assertEquals(
                    "anthropic · claude-3-7-sonnet · total 225 tokens",
                    withUsage.runUsageSummaryByRunId[run.id].textOrNull(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }
}

private suspend fun ReceiveTurbine<TasksUiState>.awaitState(
    predicate: (TasksUiState) -> Boolean,
): TasksUiState =
    withTurbineTimeout(20.seconds) {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) {
                return@withTurbineTimeout item
            }
        }
        error("unreachable")
    }

private fun intervalDraft(minutes: String) =
    TaskScheduleDraft(
        kind = TaskScheduleKindUi.Interval,
        onceDate = null,
        onceTime = null,
        intervalMinutes = minutes,
        cronExpression = "",
    )

private fun testForm(
    name: String = "Brief",
    prompt: String = "Summarize",
    schedule: TaskScheduleDraft = intervalDraft("60"),
    executionMode: TaskExecutionMode = TaskExecutionMode.MainSession,
) = TaskForm(
    name = name,
    prompt = prompt,
    schedule = schedule,
    executionMode = executionMode,
    targetSessionId = "main",
    precise = false,
)
