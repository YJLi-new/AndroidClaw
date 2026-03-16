package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import app.cash.turbine.test
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TaskRepositoryTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() = runTest {
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
    fun `create task emits observeTasks and preserves typed schedule mapping`() = runTest {
        val emissions = mutableListOf<List<ai.androidclaw.data.model.Task>>()
        lateinit var created: ai.androidclaw.data.model.Task
        repository.observeTasks().test {
            emissions += awaitItem()

            created = repository.createTask(
                name = "Morning check",
                prompt = "Check status",
                schedule = TaskSchedule.Interval(
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
    fun `due task filtering and task run lifecycle round trip through repository`() = runTest {
        val dueTask = repository.createTask(
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

        val completedRun = createdRun.copy(
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
    fun `trimRunsOlderThan removes only historical task runs`() = runTest {
        val task = repository.createTask(
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
}
