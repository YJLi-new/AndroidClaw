package ai.androidclaw.runtime.scheduler

import ai.androidclaw.app.AndroidClawApplication
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.model.TaskRunStatus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import java.time.Instant
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
class TaskExecutionWorkerSmokeTest {
    private lateinit var application: AndroidClawApplication

    @Before
    fun setUp() = runBlocking {
        application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as AndroidClawApplication
        withContext(Dispatchers.IO) {
            application.container.database.clearAllTables()
        }
        application.container.settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        application.container.ensureMainSession()
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.IO) {
            application.container.database.clearAllTables()
        }
        application.container.settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
    }

    @Test
    fun scheduledTaskPersistsRunHistoryAndAssistantMessage() = runBlocking {
        val mainSession = application.container.sessionRepository.getOrCreateMainSession()
        val task = application.container.taskRepository.createTask(
            name = "Device smoke task",
            prompt = "Summarize the session",
            schedule = TaskSchedule.Once(Instant.parse("2026-03-08T00:00:00Z")),
            executionMode = TaskExecutionMode.MainSession,
            targetSessionId = mainSession.id,
        )
        val worker = TestListenableWorkerBuilder<TaskExecutionWorker>(application)
            .setInputData(
                Data.Builder()
                    .putString(TaskExecutionWorker.KEY_TASK_ID, task.id)
                    .putString(TaskExecutionWorker.KEY_TRIGGER, TaskTrigger.Scheduled.storageValue)
                    .putLong(
                        TaskExecutionWorker.KEY_SCHEDULED_AT_EPOCH_MS,
                        (task.nextRunAt ?: Instant.parse("2026-03-08T00:00:00Z")).toEpochMilli(),
                    )
                    .build(),
            )
            .build()

        val result = worker.doWork()

        val latestRun = application.container.taskRepository.getLatestRun(task.id)
        val updatedTask = application.container.taskRepository.getTask(task.id)
        val messages = application.container.messageRepository.observeMessages(mainSession.id).first()

        assertTrue(result is ListenableWorker.Result.Success)
        assertNotNull(latestRun)
        assertEquals(TaskRunStatus.Success, latestRun?.status)
        assertEquals(task.id, latestRun?.taskId)
        assertNotNull(latestRun?.finishedAt)
        assertEquals(null, updatedTask?.nextRunAt)
        assertNotNull(updatedTask?.lastRunAt)
        assertTrue(
            messages.any { message ->
                message.taskRunId == latestRun?.id &&
                    message.role == MessageRole.Assistant &&
                    message.content.contains("Run mode: scheduled")
            },
        )
    }
}
