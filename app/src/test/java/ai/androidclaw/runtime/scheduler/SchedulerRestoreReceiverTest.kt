package ai.androidclaw.runtime.scheduler

import ai.androidclaw.app.AndroidClawApplication
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.TaskRepository
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SchedulerRestoreReceiverTest {
    private lateinit var application: AndroidClawApplication
    private lateinit var database: AndroidClawDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var eventLogRepository: EventLogRepository
    private lateinit var coordinator: SchedulerCoordinator

    @Before
    fun setUp() =
        runTest {
            application = ApplicationProvider.getApplicationContext()
            WorkManagerTestInitHelper.initializeTestWorkManager(
                application,
                Configuration
                    .Builder()
                    .setWorkerFactory(application.container.workerFactory)
                    .build(),
            )
            database = buildTestDatabase(application)
            taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
            eventLogRepository = EventLogRepository(database.eventLogDao())
            coordinator =
                SchedulerCoordinator(
                    application = application,
                    clock = java.time.Clock.fixed(Instant.parse("2026-03-09T00:00:00Z"), java.time.ZoneOffset.UTC),
                    taskRepository = taskRepository,
                    eventLogRepository = eventLogRepository,
                )
        }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `restore action matcher includes supported broadcasts only`() {
        assertTrue(shouldHandleSchedulerRestore(android.content.Intent.ACTION_BOOT_COMPLETED))
        assertTrue(shouldHandleSchedulerRestore(android.content.Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(shouldHandleSchedulerRestore(android.content.Intent.ACTION_TIME_CHANGED))
        assertTrue(shouldHandleSchedulerRestore(android.content.Intent.ACTION_TIMEZONE_CHANGED))
        assertFalse(shouldHandleSchedulerRestore("ai.androidclaw.UNKNOWN"))
        assertFalse(shouldHandleSchedulerRestore(null))
    }

    @Test
    fun `handleSchedulerRestore logs restore event and reschedules enabled tasks`() =
        runTest {
            val task =
                taskRepository.createTask(
                    name = "Restore me",
                    prompt = "Ping after restore",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                    precise = false,
                )

            handleSchedulerRestore(
                eventLogRepository = eventLogRepository,
                rescheduleAll = coordinator::rescheduleAll,
                action = android.content.Intent.ACTION_MY_PACKAGE_REPLACED,
            )

            val workInfos =
                WorkManager
                    .getInstance(application)
                    .getWorkInfosForUniqueWork(SchedulerCoordinator.nextWorkName(task.id))
                    .get()
            val events = eventLogRepository.observeRecent(limit = 10).first()

            assertEquals(1, workInfos.size)
            assertEquals(WorkInfo.State.ENQUEUED, workInfos.single().state)
            assertTrue(
                events.any { event ->
                    event.message == "Scheduler restore broadcast received." &&
                        event.details?.contains(android.content.Intent.ACTION_MY_PACKAGE_REPLACED) == true
                },
            )
        }
}
