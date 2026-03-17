package ai.androidclaw.runtime.scheduler

import ai.androidclaw.app.AndroidClawApplication
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.model.MessageRole
import java.time.ZoneOffset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class TaskExecutionWorkerTest {
    private lateinit var application: AndroidClawApplication

    @Before
    fun setUp() = runTest {
        application = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            application,
            Configuration.Builder()
                .setWorkerFactory(application.container.workerFactory)
                .build(),
        )
        withContext(Dispatchers.IO) {
            application.container.database.clearAllTables()
        }
        requireNotNull(application.getSystemService(android.app.NotificationManager::class.java)).cancelAll()
        application.container.settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        application.container.ensureMainSession()
    }

    @After
    fun tearDown() = runTest {
        withContext(Dispatchers.IO) {
            application.container.database.clearAllTables()
        }
        requireNotNull(application.getSystemService(android.app.NotificationManager::class.java)).cancelAll()
        application.container.settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
    }

    @Test
    fun `scheduled once task creates durable run and main-session output`() = runTest {
        val mainSession = application.container.sessionRepository.getOrCreateMainSession()
        val task = application.container.taskRepository.createTask(
            name = "Daily check",
            prompt = "Summarize the session",
            schedule = TaskSchedule.Once(Instant.parse("2026-03-08T00:00:00Z")),
            executionMode = TaskExecutionMode.MainSession,
            targetSessionId = mainSession.id,
        )
        val worker = buildWorker(
            taskId = task.id,
            trigger = TaskTrigger.Scheduled,
            scheduledAt = task.nextRunAt ?: Instant.parse("2026-03-08T00:00:00Z"),
        )

        val result = worker.doWork()

        val latestRun = application.container.taskRepository.getLatestRun(task.id)
        val updatedTask = application.container.taskRepository.getTask(task.id)
        val messages = application.container.messageRepository.observeMessages(mainSession.id).first()
        val notificationManager = requireNotNull(application.getSystemService(android.app.NotificationManager::class.java))
        val notifications = shadowOf(notificationManager).allNotifications

        assertTrue(result is ListenableWorker.Result.Success)
        assertNotNull(latestRun)
        assertEquals(ai.androidclaw.data.model.TaskRunStatus.Success, latestRun?.status)
        assertNull(updatedTask?.nextRunAt)
        assertNotNull(updatedTask?.lastRunAt)
        assertTrue(
            notifications.any { notification ->
                notification.channelId == TASK_RESULTS_NOTIFICATION_CHANNEL_ID &&
                    notification.extras.getString("android.title") == "Task completed: Daily check"
            },
        )
        assertTrue(
            messages.any { message ->
                message.taskRunId == latestRun?.id &&
                    message.role == MessageRole.Assistant &&
                    message.content.contains("Run mode: scheduled")
            },
        )
    }

    @Test
    fun `manual run keeps future schedule intact`() = runTest {
        val mainSession = application.container.sessionRepository.getOrCreateMainSession()
        val task = application.container.taskRepository.createTask(
            name = "Manual only",
            prompt = "Check now",
            schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
            executionMode = TaskExecutionMode.MainSession,
            targetSessionId = mainSession.id,
        )
        val originalNextRunAt = task.nextRunAt
        val worker = buildWorker(
            taskId = task.id,
            trigger = TaskTrigger.Manual,
            scheduledAt = Instant.parse("2026-03-09T00:00:00Z"),
        )

        val result = worker.doWork()

        val latestRun = application.container.taskRepository.getLatestRun(task.id)
        val updatedTask = application.container.taskRepository.getTask(task.id)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(ai.androidclaw.data.model.TaskRunStatus.Success, latestRun?.status)
        assertEquals(originalNextRunAt, updatedTask?.nextRunAt)
        assertNotNull(updatedTask?.lastRunAt)
    }

    @Test
    fun `scheduled interval task reschedules itself after success`() = runTest {
        val mainSession = application.container.sessionRepository.getOrCreateMainSession()
        val createdTask = application.container.taskRepository.createTask(
            name = "Recurring check",
            prompt = "Check status",
            schedule = TaskSchedule.Interval(
                anchorAt = Instant.parse("2026-03-08T00:00:00Z"),
                repeatEvery = Duration.ofHours(1),
            ),
            executionMode = TaskExecutionMode.MainSession,
            targetSessionId = mainSession.id,
        )
        val dueAt = Instant.now().minusSeconds(60)
        val dueTask = createdTask.copy(
            nextRunAt = dueAt,
            updatedAt = Instant.now(),
        )
        application.container.taskRepository.updateTask(dueTask)
        val worker = buildWorker(
            taskId = dueTask.id,
            trigger = TaskTrigger.Scheduled,
            scheduledAt = dueTask.nextRunAt ?: dueAt,
        )

        val result = worker.doWork()

        val latestRun = application.container.taskRepository.getLatestRun(dueTask.id)
        val updatedTask = application.container.taskRepository.getTask(dueTask.id)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(ai.androidclaw.data.model.TaskRunStatus.Success, latestRun?.status)
        assertNotNull(updatedTask?.nextRunAt)
        assertNotNull(updatedTask?.lastRunAt)
        assertTrue(updatedTask?.nextRunAt?.isAfter(updatedTask.lastRunAt) == true)
    }

    @Test
    fun `scheduled cron task reschedules itself after success`() = runTest {
        val mainSession = application.container.sessionRepository.getOrCreateMainSession()
        val createdTask = application.container.taskRepository.createTask(
            name = "Daily cron",
            prompt = "Summarize today",
            schedule = TaskSchedule.Cron(
                expression = CronExpression.parse("@daily"),
                zoneId = ZoneOffset.UTC,
            ),
            executionMode = TaskExecutionMode.MainSession,
            targetSessionId = mainSession.id,
        )
        val dueAt = Instant.now().minusSeconds(60)
        val dueTask = createdTask.copy(
            nextRunAt = dueAt,
            updatedAt = Instant.now(),
        )
        application.container.taskRepository.updateTask(dueTask)
        val worker = buildWorker(
            taskId = dueTask.id,
            trigger = TaskTrigger.Scheduled,
            scheduledAt = dueAt,
        )

        val result = worker.doWork()

        val updatedTask = application.container.taskRepository.getTask(dueTask.id)

        assertTrue(result is ListenableWorker.Result.Success)
        assertNotNull(updatedTask?.nextRunAt)
        assertNotNull(updatedTask?.lastRunAt)
        assertTrue(updatedTask?.nextRunAt?.isAfter(updatedTask.lastRunAt) == true)
    }

    @Test
    fun `isolated execution mode delivers summary to target session`() = runTest {
        val mainSession = application.container.sessionRepository.getOrCreateMainSession()
        val task = application.container.taskRepository.createTask(
            name = "Isolated analysis",
            prompt = "Analyze privately",
            schedule = TaskSchedule.Once(Instant.parse("2026-03-08T00:00:00Z")),
            executionMode = TaskExecutionMode.IsolatedSession,
            targetSessionId = mainSession.id,
        )
        val worker = buildWorker(
            taskId = task.id,
            trigger = TaskTrigger.Scheduled,
            scheduledAt = task.nextRunAt ?: Instant.parse("2026-03-08T00:00:00Z"),
        )

        val result = worker.doWork()

        val sessions = application.container.sessionRepository.observeSessions().first()
        val mainMessages = application.container.messageRepository.observeMessages(mainSession.id).first()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(sessions.size >= 2)
        assertTrue(
            mainMessages.any { message ->
                message.role == MessageRole.Assistant &&
                    message.content.contains("completed in isolated session")
            },
        )
    }

    private fun buildWorker(
        taskId: String,
        trigger: TaskTrigger,
        scheduledAt: Instant,
    ): TaskExecutionWorker {
        return TestListenableWorkerBuilder<TaskExecutionWorker>(application)
            .setWorkerFactory(application.container.workerFactory)
            .setInputData(
                Data.Builder()
                    .putString(TaskExecutionWorker.KEY_TASK_ID, taskId)
                    .putString(TaskExecutionWorker.KEY_TRIGGER, trigger.storageValue)
                    .putLong(TaskExecutionWorker.KEY_SCHEDULED_AT_EPOCH_MS, scheduledAt.toEpochMilli())
                    .build(),
            )
            .build()
    }
}
