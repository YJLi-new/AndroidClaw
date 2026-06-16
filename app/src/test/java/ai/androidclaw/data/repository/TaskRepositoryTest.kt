package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.db.entity.TaskEntity
import ai.androidclaw.data.db.entity.TaskRunEntity
import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TaskRepositoryTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() =
        runTest {
            database = buildTestDatabase(ApplicationProvider.getApplicationContext())
            repository = TaskRepository(database.taskDao(), database.taskRunDao())
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
        }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `create task emits observeTasks and preserves typed schedule mapping`() =
        runTest {
            val emissions = mutableListOf<List<ai.androidclaw.data.model.Task>>()
            lateinit var created: ai.androidclaw.data.model.Task
            repository.observeTasks().test {
                emissions += awaitItem()

                created =
                    repository.createTask(
                        name = "Morning check",
                        prompt = "Check status",
                        schedule =
                            TaskSchedule.Interval(
                                anchorAt = Instant.ofEpochMilli(1_000L),
                                repeatEvery = Duration.ofHours(1),
                            ),
                        executionMode = TaskExecutionMode.MainSession,
                        targetSessionId = "main",
                        precise = true,
                        maxRetries = 5,
                    )

                emissions += awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            val stored = repository.getTask(created.id)
            assertNotNull(stored)
            assertEquals(created.schedule, stored?.schedule)
            assertEquals(TaskExecutionMode.MainSession, stored?.executionMode)
            assertTrue(stored?.precise == true)
            assertEquals(5, stored?.maxRetries)
            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), emissions.first())
            assertEquals(listOf(created.id), emissions.last().map { it.id })
        }

    @Test
    fun `search tasks matches names and prompts with literal wildcard text`() =
        runTest {
            val named =
                repository.createTask(
                    name = "Project Alpha",
                    prompt = "Daily status",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
            val prompted =
                repository.createTask(
                    name = "Morning task",
                    prompt = "Summarize Alpha milestones",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(20L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
            val literal =
                repository.createTask(
                    name = "Escaped task",
                    prompt = "Path is 100%_ready",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(30L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
            repository.createTask(
                name = "Beta notes",
                prompt = "No matching text",
                schedule = TaskSchedule.Once(Instant.ofEpochMilli(40L)),
                executionMode = TaskExecutionMode.MainSession,
                targetSessionId = "main",
            )

            assertEquals(
                listOf(named.id, prompted.id),
                repository.searchTasks("Alpha", limit = 10).map { it.id },
            )
            assertEquals(listOf(literal.id), repository.searchTasks("%_", limit = 10).map { it.id })
            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), repository.searchTasks("Alpha", limit = 0))
            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), repository.searchTasks("   ", limit = 10))
        }

    @Test
    fun `due task filtering and task run lifecycle round trip through repository`() =
        runTest {
            val dueTask =
                repository.createTask(
                    name = "Due task",
                    prompt = "Run now",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
            repository.createTask(
                name = "Future task",
                prompt = "Run later",
                schedule = TaskSchedule.Once(Instant.now().plusSeconds(3_600)),
                executionMode = TaskExecutionMode.MainSession,
                targetSessionId = "main",
            )

            val due = repository.getEnabledTasksDueBefore(Instant.now())
            assertEquals(listOf(dueTask.id), due.map { it.id })

            val emissions = mutableListOf<List<ai.androidclaw.data.model.TaskRun>>()
            lateinit var createdRun: ai.androidclaw.data.model.TaskRun
            repository.observeRuns(dueTask.id).test {
                emissions += awaitItem()
                createdRun = repository.recordRun(dueTask.id)
                emissions += awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            val completedRun =
                createdRun.copy(
                    status = TaskRunStatus.Success,
                    startedAt = createdRun.scheduledAt,
                    finishedAt = createdRun.scheduledAt.plusSeconds(1),
                    resultSummary = "Completed",
                )
            repository.updateRun(completedRun)

            val latest = repository.getLatestRun(dueTask.id)
            assertEquals(TaskRunStatus.Pending, emissions.last().single().status)
            assertEquals(TaskRunStatus.Success, latest?.status)
            assertEquals("Completed", latest?.resultSummary)
        }

    @Test
    fun `upcoming enabled task query returns scheduled tasks in next run order`() =
        runTest {
            database.taskDao().insert(taskEntity(id = "later-task", enabled = true, nextRunAt = 30L))
            database.taskDao().insert(taskEntity(id = "disabled-task", enabled = false, nextRunAt = 5L))
            database.taskDao().insert(taskEntity(id = "unscheduled-task", enabled = true, nextRunAt = null))
            database.taskDao().insert(taskEntity(id = "sooner-task", enabled = true, nextRunAt = 20L))
            database.taskDao().insert(invalidTaskEntity(id = "invalid-task", nextRunAt = 10L))

            val upcoming = repository.getUpcomingEnabledTasks(limit = 10)
            val limited = repository.getUpcomingEnabledTasks(limit = 1)

            assertEquals(listOf("sooner-task", "later-task"), upcoming.map { task -> task.id })
            assertEquals(listOf("sooner-task"), limited.map { task -> task.id })
            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), repository.getUpcomingEnabledTasks(limit = 0))
        }

    @Test
    fun `task stats aggregate task scheduling and run status state`() =
        runTest {
            database.taskDao().insert(
                taskEntity(
                    id = "due-task",
                    scheduleKind = "once",
                    executionMode = "MAIN_SESSION",
                    enabled = true,
                    nextRunAt = 1_000L,
                    updatedAt = 1_500L,
                ),
            )
            database.taskDao().insert(
                taskEntity(
                    id = "future-task",
                    scheduleKind = "interval",
                    executionMode = "ISOLATED_SESSION",
                    enabled = true,
                    nextRunAt = 10_000L,
                    updatedAt = 2_500L,
                ),
            )
            database.taskDao().insert(
                taskEntity(
                    id = "disabled-task",
                    scheduleKind = "once",
                    executionMode = "MAIN_SESSION",
                    enabled = false,
                    nextRunAt = 500L,
                    updatedAt = 3_500L,
                ),
            )
            database.taskRunDao().insert(
                taskRunEntity(
                    id = "success-run",
                    taskId = "due-task",
                    status = "SUCCESS",
                    scheduledAt = 1_100L,
                ),
            )
            database.taskRunDao().insert(
                taskRunEntity(
                    id = "failure-run",
                    taskId = "future-task",
                    status = "FAILURE",
                    scheduledAt = 2_200L,
                ),
            )

            val stats = repository.getTaskStats(Instant.ofEpochMilli(2_000L))
            val kindStats = stats.scheduleKindStats.associate { item -> item.scheduleKind to item.taskCount }
            val modeStats = stats.executionModeStats.associate { item -> item.executionMode to item.taskCount }
            val runStats = stats.runStatusStats.associate { item -> item.status to item.runCount }

            assertEquals(3L, stats.totalTaskCount)
            assertEquals(2L, stats.enabledTaskCount)
            assertEquals(1L, stats.disabledTaskCount)
            assertEquals(3L, stats.scheduledTaskCount)
            assertEquals(1L, stats.dueTaskCount)
            assertEquals(Instant.ofEpochMilli(1_000L), stats.nextEnabledRunAt)
            assertEquals(Instant.ofEpochMilli(3_500L), stats.newestTaskUpdatedAt)
            assertEquals(mapOf("interval" to 1L, "once" to 2L), kindStats)
            assertEquals(2L, modeStats.getValue(TaskExecutionMode.MainSession))
            assertEquals(1L, modeStats.getValue(TaskExecutionMode.IsolatedSession))
            assertEquals(2L, stats.totalRunCount)
            assertEquals(Instant.ofEpochMilli(1_100L), stats.oldestRunScheduledAt)
            assertEquals(Instant.ofEpochMilli(2_200L), stats.newestRunScheduledAt)
            assertEquals(1L, runStats.getValue(TaskRunStatus.Success))
            assertEquals(1L, runStats.getValue(TaskRunStatus.Failure))
        }

    @Test
    fun `get recent runs returns bounded newest-first history`() =
        runTest {
            val task =
                repository.createTask(
                    name = "History task",
                    prompt = "Keep run history",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
            val older = repository.recordRun(task.id, scheduledAt = Instant.ofEpochMilli(20L))
            val newer = repository.recordRun(task.id, scheduledAt = Instant.ofEpochMilli(30L))

            repository.updateRun(older.copy(status = TaskRunStatus.Failure, errorCode = "OLD"))
            repository.updateRun(newer.copy(status = TaskRunStatus.Success, resultSummary = "Newest"))

            val recent = repository.getRecentRuns(task.id, limit = 1)

            assertEquals(listOf(newer.id), recent.map { it.id })
            assertEquals(TaskRunStatus.Success, recent.single().status)
            assertEquals("Newest", recent.single().resultSummary)
            assertEquals(emptyList<ai.androidclaw.data.model.TaskRun>(), repository.getRecentRuns(task.id, limit = 0))
        }

    @Test
    fun `get run returns exact run by id`() =
        runTest {
            val task =
                repository.createTask(
                    name = "Exact run task",
                    prompt = "Load one run",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
            val run = repository.recordRun(task.id, scheduledAt = Instant.ofEpochMilli(20L))
            repository.updateRun(run.copy(status = TaskRunStatus.Success, resultSummary = "Loaded exactly"))

            val loaded = repository.getRun(run.id)

            assertEquals(run.id, loaded?.id)
            assertEquals(task.id, loaded?.taskId)
            assertEquals(TaskRunStatus.Success, loaded?.status)
            assertEquals("Loaded exactly", loaded?.resultSummary)
            assertNull(repository.getRun("missing-run"))
        }

    @Test
    fun `trimRunsOlderThan removes only historical task runs`() =
        runTest {
            val task =
                repository.createTask(
                    name = "Retention task",
                    prompt = "Keep the latest run",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
            database.taskRunDao().insert(
                ai.androidclaw.data.db.entity.TaskRunEntity(
                    id = "run-old",
                    taskId = task.id,
                    status = "SUCCESS",
                    scheduledAt = 1_000L,
                    startedAt = 1_100L,
                    finishedAt = 1_200L,
                    errorCode = null,
                    errorMessage = null,
                    resultSummary = "Old run",
                    outputMessageId = null,
                ),
            )
            database.taskRunDao().insert(
                ai.androidclaw.data.db.entity.TaskRunEntity(
                    id = "run-new",
                    taskId = task.id,
                    status = "SUCCESS",
                    scheduledAt = 9_000L,
                    startedAt = 9_100L,
                    finishedAt = 9_200L,
                    errorCode = null,
                    errorMessage = null,
                    resultSummary = "New run",
                    outputMessageId = null,
                ),
            )

            val trimmed = repository.trimRunsOlderThan(Instant.ofEpochMilli(5_000L))

            assertEquals(1, trimmed)
            val remaining = repository.observeRuns(task.id).first()
            assertEquals(listOf("run-new"), remaining.map { it.id })
        }

    @Test
    fun `getTask returns null for task with invalid persisted schedule`() =
        runTest {
            database.taskDao().insert(invalidTaskEntity(id = "bad-task"))

            assertNull(repository.getTask("bad-task"))
        }

    @Test
    fun `observeTasks skips tasks with invalid persisted schedules`() =
        runTest {
            val validTask =
                repository.createTask(
                    name = "Valid task",
                    prompt = "Run valid task",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
            database.taskDao().insert(invalidTaskEntity(id = "bad-task"))

            val observed = repository.observeTasks().first()

            assertEquals(listOf(validTask.id), observed.map { it.id })
        }

    @Test
    fun `due task filtering skips tasks with invalid persisted schedules`() =
        runTest {
            database.taskDao().insert(invalidTaskEntity(id = "bad-task", nextRunAt = 1L))

            val due = repository.getEnabledTasksDueBefore(Instant.ofEpochMilli(2L))

            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), due)
        }

    @Test
    fun `task reads skip tasks with malformed persisted schedule json`() =
        runTest {
            database.taskDao().insert(
                invalidTaskEntity(
                    id = "bad-json-task",
                    scheduleSpec = """{"kind":"interval","anchorAtEpochMillis":""",
                    nextRunAt = 1L,
                ),
            )

            assertNull(repository.getTask("bad-json-task"))
            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), repository.observeTasks().first())
            assertEquals(
                emptyList<ai.androidclaw.data.model.Task>(),
                repository.getEnabledTasksDueBefore(Instant.ofEpochMilli(2L)),
            )
        }

    @Test
    fun `create task bounds text fields before persistence`() =
        runTest {
            val longName = "n".repeat(TASK_NAME_MAX_CHARS + 20)
            val longPrompt = "p".repeat(TASK_PROMPT_MAX_CHARS + 20)

            val created =
                repository.createTask(
                    name = longName,
                    prompt = longPrompt,
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
            val raw = database.taskDao().getById(created.id)
            val stored = repository.getTask(created.id)

            assertEquals(longName.take(TASK_NAME_MAX_CHARS), created.name)
            assertEquals(longPrompt.take(TASK_PROMPT_MAX_CHARS), created.prompt)
            assertEquals(longName.take(TASK_NAME_MAX_CHARS), raw?.name)
            assertEquals(longPrompt.take(TASK_PROMPT_MAX_CHARS), raw?.prompt)
            assertEquals(longName.take(TASK_NAME_MAX_CHARS), stored?.name)
            assertEquals(longPrompt.take(TASK_PROMPT_MAX_CHARS), stored?.prompt)
        }

    @Test
    fun `update task bounds text fields before persistence`() =
        runTest {
            val longName = "u".repeat(TASK_NAME_MAX_CHARS + 20)
            val longPrompt = "q".repeat(TASK_PROMPT_MAX_CHARS + 20)
            val longTargetSessionId = "target-" + "x".repeat(TASK_TARGET_SESSION_ID_MAX_CHARS + 20)
            val boundedTargetSessionId = longTargetSessionId.take(TASK_TARGET_SESSION_ID_MAX_CHARS)
            database.sessionDao().insert(
                SessionEntity(
                    id = boundedTargetSessionId,
                    title = "Bounded target",
                    isMain = false,
                    createdAt = 2L,
                    updatedAt = 2L,
                    archivedAt = null,
                    summaryText = null,
                ),
            )
            val created =
                repository.createTask(
                    name = "Update bounds",
                    prompt = "Before update",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )

            repository.updateTask(
                created.copy(
                    name = longName,
                    prompt = longPrompt,
                    targetSessionId = longTargetSessionId,
                ),
            )
            val raw = database.taskDao().getById(created.id)
            val stored = repository.getTask(created.id)

            assertEquals(longName.take(TASK_NAME_MAX_CHARS), raw?.name)
            assertEquals(longPrompt.take(TASK_PROMPT_MAX_CHARS), raw?.prompt)
            assertEquals(boundedTargetSessionId, raw?.targetSessionId)
            assertEquals(longName.take(TASK_NAME_MAX_CHARS), stored?.name)
            assertEquals(longPrompt.take(TASK_PROMPT_MAX_CHARS), stored?.prompt)
            assertEquals(boundedTargetSessionId, stored?.targetSessionId)
        }

    @Test
    fun `task reads bound legacy oversized text fields`() =
        runTest {
            val longName = "legacy-name".repeat(20)
            val longPrompt = "legacy-prompt".repeat(4_000)
            val longTargetSessionId = "legacy-target-" + "z".repeat(TASK_TARGET_SESSION_ID_MAX_CHARS + 20)
            database.sessionDao().insert(
                SessionEntity(
                    id = longTargetSessionId,
                    title = "Legacy oversized target",
                    isMain = false,
                    createdAt = 2L,
                    updatedAt = 2L,
                    archivedAt = null,
                    summaryText = null,
                ),
            )
            database.taskDao().insert(
                taskEntity(
                    id = "legacy-oversized-task",
                    name = longName,
                    prompt = longPrompt,
                    targetSessionId = longTargetSessionId,
                ),
            )

            val stored = repository.getTask("legacy-oversized-task")
            val observed = repository.observeTasks().first()
            val due = repository.getEnabledTasksDueBefore(Instant.ofEpochMilli(2L))

            assertEquals(longName.take(TASK_NAME_MAX_CHARS), stored?.name)
            assertEquals(longPrompt.take(TASK_PROMPT_MAX_CHARS), stored?.prompt)
            assertEquals(longTargetSessionId.take(TASK_TARGET_SESSION_ID_MAX_CHARS), stored?.targetSessionId)
            assertEquals(longName.take(TASK_NAME_MAX_CHARS), observed.single().name)
            assertEquals(longPrompt.take(TASK_PROMPT_MAX_CHARS), observed.single().prompt)
            assertEquals(longTargetSessionId.take(TASK_TARGET_SESSION_ID_MAX_CHARS), observed.single().targetSessionId)
            assertEquals(longName.take(TASK_NAME_MAX_CHARS), due.single().name)
            assertEquals(longPrompt.take(TASK_PROMPT_MAX_CHARS), due.single().prompt)
            assertEquals(longTargetSessionId.take(TASK_TARGET_SESSION_ID_MAX_CHARS), due.single().targetSessionId)
        }

    @Test
    fun `create task clamps negative max retries`() =
        runTest {
            val created =
                repository.createTask(
                    name = "No retry task",
                    prompt = "Do not retry",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                    maxRetries = -5,
                )

            val raw = database.taskDao().getById(created.id)
            val stored = repository.getTask(created.id)

            assertEquals(0, created.maxRetries)
            assertEquals(0, raw?.maxRetries)
            assertEquals(0, stored?.maxRetries)
        }

    @Test
    fun `update task clamps negative retry counters`() =
        runTest {
            val created =
                repository.createTask(
                    name = "Retry task",
                    prompt = "Retry safely",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )

            repository.updateTask(
                created.copy(
                    failureCount = -3,
                    maxRetries = -7,
                ),
            )
            val raw = database.taskDao().getById(created.id)
            val stored = repository.getTask(created.id)

            assertEquals(0, raw?.failureCount)
            assertEquals(0, raw?.maxRetries)
            assertEquals(0, stored?.failureCount)
            assertEquals(0, stored?.maxRetries)
        }

    @Test
    fun `task reads clamp negative persisted retry counters`() =
        runTest {
            database.taskDao().insert(
                taskEntity(
                    id = "negative-counters-task",
                    failureCount = -11,
                    maxRetries = -2,
                ),
            )

            val stored = repository.getTask("negative-counters-task")

            assertEquals(0, stored?.failureCount)
            assertEquals(0, stored?.maxRetries)
        }

    @Test
    fun `update run bounds diagnostic text before persistence`() =
        runTest {
            val task =
                repository.createTask(
                    name = "Diagnostic task",
                    prompt = "Write bounded run diagnostics",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
            val run = repository.recordRun(task.id, scheduledAt = Instant.ofEpochMilli(10L))
            val longError = "e".repeat(TASK_RUN_ERROR_MESSAGE_MAX_CHARS + 25)
            val longSummary = "s".repeat(TASK_RUN_RESULT_SUMMARY_MAX_CHARS + 25)

            repository.updateRun(
                run.copy(
                    status = TaskRunStatus.Failure,
                    errorMessage = longError,
                    resultSummary = longSummary,
                ),
            )
            val raw = database.taskRunDao().getLatestByTaskId(task.id)
            val latest = repository.getLatestRun(task.id)

            assertEquals(longError.take(TASK_RUN_ERROR_MESSAGE_MAX_CHARS), raw?.errorMessage)
            assertEquals(longSummary.take(TASK_RUN_RESULT_SUMMARY_MAX_CHARS), raw?.resultSummary)
            assertEquals(longError.take(TASK_RUN_ERROR_MESSAGE_MAX_CHARS), latest?.errorMessage)
            assertEquals(longSummary.take(TASK_RUN_RESULT_SUMMARY_MAX_CHARS), latest?.resultSummary)
        }

    @Test
    fun `run reads bound legacy oversized diagnostic text`() =
        runTest {
            val task =
                repository.createTask(
                    name = "Legacy diagnostic task",
                    prompt = "Read bounded run diagnostics",
                    schedule = TaskSchedule.Once(Instant.ofEpochMilli(10L)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = "main",
                )
            val longError = "legacy-error".repeat(120)
            val longSummary = "legacy-summary".repeat(400)
            database.taskRunDao().insert(
                taskRunEntity(
                    id = "legacy-run",
                    taskId = task.id,
                    errorMessage = longError,
                    resultSummary = longSummary,
                ),
            )

            val latest = repository.getLatestRun(task.id)
            val observed = repository.observeRuns(task.id).first()

            assertEquals(longError.take(TASK_RUN_ERROR_MESSAGE_MAX_CHARS), latest?.errorMessage)
            assertEquals(longSummary.take(TASK_RUN_RESULT_SUMMARY_MAX_CHARS), latest?.resultSummary)
            assertEquals(longError.take(TASK_RUN_ERROR_MESSAGE_MAX_CHARS), observed.single().errorMessage)
            assertEquals(longSummary.take(TASK_RUN_RESULT_SUMMARY_MAX_CHARS), observed.single().resultSummary)
        }
}

private fun taskEntity(
    id: String,
    name: String = "Stored task",
    prompt: String = "This task was inserted directly.",
    scheduleKind: String = "once",
    scheduleSpec: String = """{"kind":"once","atEpochMillis":10}""",
    executionMode: String = "MAIN_SESSION",
    targetSessionId: String? = "main",
    enabled: Boolean = true,
    nextRunAt: Long? = 1L,
    failureCount: Int = 0,
    maxRetries: Int = 3,
    updatedAt: Long = 1L,
): TaskEntity =
    TaskEntity(
        id = id,
        name = name,
        prompt = prompt,
        scheduleKind = scheduleKind,
        scheduleSpec = scheduleSpec,
        executionMode = executionMode,
        targetSessionId = targetSessionId,
        enabled = enabled,
        precise = false,
        nextRunAt = nextRunAt,
        lastRunAt = null,
        failureCount = failureCount,
        maxRetries = maxRetries,
        createdAt = 1L,
        updatedAt = updatedAt,
    )

private fun invalidTaskEntity(
    id: String,
    scheduleSpec: String = """{"kind":"interval","anchorAtEpochMillis":0,"intervalMillis":0}""",
    nextRunAt: Long? = 1L,
): TaskEntity =
    taskEntity(
        id = id,
        scheduleKind = "interval",
        scheduleSpec = scheduleSpec,
        nextRunAt = nextRunAt,
    )

private fun taskRunEntity(
    id: String,
    taskId: String,
    status: String = "FAILURE",
    scheduledAt: Long = 10L,
    errorMessage: String? = null,
    resultSummary: String? = null,
): TaskRunEntity =
    TaskRunEntity(
        id = id,
        taskId = taskId,
        status = status,
        scheduledAt = scheduledAt,
        startedAt = scheduledAt,
        finishedAt = scheduledAt + 1L,
        errorCode = "LEGACY_ERROR",
        errorMessage = errorMessage,
        resultSummary = resultSummary,
        outputMessageId = null,
    )
