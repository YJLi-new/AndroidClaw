package ai.androidclaw.app

import ai.androidclaw.data.db.entity.EventLogEntity
import ai.androidclaw.data.db.entity.MessageEntity
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.db.entity.TaskRunEntity
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupMaintenanceIntegrationTest {
    private lateinit var application: AndroidClawApplication

    @Before
    fun setUp() = runBlocking {
        application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as AndroidClawApplication
        WorkManagerTestInitHelper.initializeTestWorkManager(
            application,
            Configuration.Builder()
                .setWorkerFactory(application.container.workerFactory)
                .build(),
        )
        WorkManager.getInstance(application).cancelAllWork().result.get()
        withContext(Dispatchers.IO) {
            application.container.database.clearAllTables()
        }
    }

    @After
    fun tearDown() = runBlocking {
        WorkManager.getInstance(application).cancelAllWork().result.get()
        withContext(Dispatchers.IO) {
            application.container.database.clearAllTables()
        }
    }

    @Test
    fun startupMaintenance_recreatesMainSession_prunesHistory_andReschedulesPendingWork() = runBlocking {
        val now = Instant.now()
        val oldInstant = now.minus(60, ChronoUnit.DAYS)
        val freshInstant = now.minus(1, ChronoUnit.DAYS)

        withContext(Dispatchers.IO) {
            application.container.database.sessionDao().insert(
                SessionEntity(
                    id = "archive",
                    title = "Archive",
                    isMain = false,
                    createdAt = oldInstant.toEpochMilli(),
                    updatedAt = oldInstant.toEpochMilli(),
                    archivedAt = freshInstant.toEpochMilli(),
                    summaryText = "still available",
                ),
            )
            application.container.database.messageDao().insert(
                MessageEntity(
                    id = "archive-message",
                    sessionId = "archive",
                    role = "system",
                    content = "persist me",
                    createdAt = freshInstant.toEpochMilli(),
                    providerMeta = null,
                    toolCallId = null,
                    taskRunId = null,
                ),
            )
        }

        val task = application.container.taskRepository.createTask(
            name = "Startup reschedule",
            prompt = "Ping later",
            schedule = TaskSchedule.Once(now.plus(10, ChronoUnit.MINUTES)),
            executionMode = TaskExecutionMode.MainSession,
            targetSessionId = null,
            precise = false,
        )

        withContext(Dispatchers.IO) {
            application.container.database.taskRunDao().insert(
                TaskRunEntity(
                    id = "run-old",
                    taskId = task.id,
                    status = "SUCCESS",
                    scheduledAt = oldInstant.toEpochMilli(),
                    startedAt = null,
                    finishedAt = null,
                    errorCode = null,
                    errorMessage = null,
                    resultSummary = "old",
                    outputMessageId = null,
                ),
            )
            application.container.database.taskRunDao().insert(
                TaskRunEntity(
                    id = "run-fresh",
                    taskId = task.id,
                    status = "RUNNING",
                    scheduledAt = freshInstant.toEpochMilli(),
                    startedAt = freshInstant.toEpochMilli(),
                    finishedAt = null,
                    errorCode = null,
                    errorMessage = null,
                    resultSummary = null,
                    outputMessageId = null,
                ),
            )
            application.container.database.eventLogDao().insert(
                EventLogEntity(
                    id = "event-old",
                    timestamp = oldInstant.toEpochMilli(),
                    category = "system",
                    level = "info",
                    message = "old event",
                    detailsJson = null,
                ),
            )
            application.container.database.eventLogDao().insert(
                EventLogEntity(
                    id = "event-fresh",
                    timestamp = freshInstant.toEpochMilli(),
                    category = "system",
                    level = "info",
                    message = "fresh event",
                    detailsJson = null,
                ),
            )
        }

        val result = application.container.startupMaintenance.run()

        val mainSession = application.container.database.sessionDao().getMainSession()
        assertNotNull(mainSession)
        assertEquals(1, application.container.database.messageDao().countBySessionId(mainSession!!.id))
        assertEquals(
            "AndroidClaw is ready.",
            application.container.messageRepository.getRecentMessages(mainSession.id, limit = 1).single().content,
        )
        assertNotNull(application.container.database.sessionDao().getById("archive"))
        assertEquals(1, application.container.database.messageDao().countBySessionId("archive"))
        assertNotNull(application.container.database.taskDao().getById(task.id))

        assertEquals(1, result.trimmedTaskRuns)
        assertEquals(1, result.trimmedEventLogs)
        assertEquals(
            listOf("run-fresh"),
            application.container.taskRepository.observeRuns(task.id).first().map { it.id },
        )

        val events = application.container.eventLogRepository.observeRecent(limit = 20).first()
        assertTrue(events.none { it.id == "event-old" })
        assertTrue(events.any { it.id == "event-fresh" })

        val workInfos = WorkManager.getInstance(application)
            .getWorkInfosForUniqueWork(SchedulerCoordinator.nextWorkName(task.id))
            .get()
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos.single().state)
    }
}
