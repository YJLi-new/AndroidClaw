package ai.androidclaw.app

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.EventLogEntity
import ai.androidclaw.data.db.entity.MessageEntity
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.db.entity.TaskRunEntity
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
class StartupMaintenanceTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var eventLogRepository: EventLogRepository

    @Before
    fun setUp() = runTest {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
        eventLogRepository = EventLogRepository(database.eventLogDao())
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
        if (this::database.isInitialized) {
            database.close()
        }
    }

    @Test
    fun `run trims retained data and then reschedules`() = runTest {
        database.sessionDao().insert(
            SessionEntity(
                id = "archive",
                title = "Archived session",
                isMain = false,
                createdAt = 2L,
                updatedAt = 2L,
                archivedAt = 3L,
                summaryText = "historical summary",
            ),
        )
        database.messageDao().insert(
            MessageEntity(
                id = "message-archive",
                sessionId = "archive",
                role = "system",
                content = "Still here",
                createdAt = Instant.parse("2026-03-08T00:00:00Z").toEpochMilli(),
                providerMeta = null,
                toolCallId = null,
                taskRunId = null,
            ),
        )
        val task = taskRepository.createTask(
            name = "Retention task",
            prompt = "Run",
            schedule = TaskSchedule.Once(Instant.parse("2026-02-01T00:00:00Z")),
            executionMode = TaskExecutionMode.MainSession,
            targetSessionId = "main",
        )
        database.taskRunDao().insert(
            TaskRunEntity(
                id = "run-old",
                taskId = task.id,
                status = "SUCCESS",
                scheduledAt = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
                startedAt = null,
                finishedAt = null,
                errorCode = null,
                errorMessage = null,
                resultSummary = null,
                outputMessageId = null,
            ),
        )
        database.taskRunDao().insert(
            TaskRunEntity(
                id = "run-fresh",
                taskId = task.id,
                status = "SUCCESS",
                scheduledAt = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli(),
                startedAt = null,
                finishedAt = null,
                errorCode = null,
                errorMessage = null,
                resultSummary = null,
                outputMessageId = null,
            ),
        )
        database.eventLogDao().insert(
            EventLogEntity(
                id = "event-old",
                timestamp = Instant.parse("2026-02-10T00:00:00Z").toEpochMilli(),
                category = "system",
                level = "info",
                message = "Old event",
                detailsJson = null,
            ),
        )
        database.eventLogDao().insert(
            EventLogEntity(
                id = "event-fresh",
                timestamp = Instant.parse("2026-03-08T00:00:00Z").toEpochMilli(),
                category = "system",
                level = "info",
                message = "Fresh event",
                detailsJson = null,
            ),
        )

        var ensuredMainSession = false
        var rescheduleCalls = 0
        val maintenance = StartupMaintenance(
            clock = Clock.fixed(Instant.parse("2026-03-09T00:00:00Z"), ZoneOffset.UTC),
            taskRepository = taskRepository,
            eventLogRepository = eventLogRepository,
            ensureMainSession = { ensuredMainSession = true },
            rescheduleAll = { rescheduleCalls += 1 },
        )

        val result = maintenance.run()

        assertTrue(ensuredMainSession)
        assertEquals(1, rescheduleCalls)
        assertEquals(1, result.trimmedTaskRuns)
        assertEquals(1, result.trimmedEventLogs)
        assertNotNull(database.sessionDao().getById("main"))
        assertNotNull(database.sessionDao().getById("archive"))
        assertEquals(1, database.messageDao().countBySessionId("archive"))
        assertNotNull(database.taskDao().getById(task.id))
        assertEquals(listOf("run-fresh"), taskRepository.observeRuns(task.id).first().map { it.id })
        assertEquals(listOf("event-fresh"), eventLogRepository.observeRecent(limit = 10).first().map { it.id })
    }

    @Test
    fun `run ensures main session before rescheduling`() = runTest {
        database.sessionDao().getMainSession()?.let { existing ->
            database.sessionDao().update(existing.copy(isMain = false))
        }

        val callOrder = mutableListOf<String>()
        val maintenance = StartupMaintenance(
            clock = Clock.fixed(Instant.parse("2026-03-09T00:00:00Z"), ZoneOffset.UTC),
            taskRepository = taskRepository,
            eventLogRepository = eventLogRepository,
            ensureMainSession = {
                callOrder += "ensure"
                database.sessionDao().insert(
                    SessionEntity(
                        id = "restored-main",
                        title = "Main session",
                        isMain = true,
                        createdAt = 10L,
                        updatedAt = 10L,
                        archivedAt = null,
                        summaryText = null,
                    ),
                )
            },
            rescheduleAll = {
                callOrder += "reschedule"
                assertNotNull(database.sessionDao().getMainSession())
            },
        )

        maintenance.run()

        assertEquals(listOf("ensure", "reschedule"), callOrder)
        assertEquals("restored-main", database.sessionDao().getMainSession()?.id)
    }
}
