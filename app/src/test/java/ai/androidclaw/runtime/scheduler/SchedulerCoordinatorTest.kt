package ai.androidclaw.runtime.scheduler

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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchedulerCoordinatorTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: TaskRepository
    private lateinit var eventLogRepository: EventLogRepository
    private lateinit var coordinator: SchedulerCoordinator

    @Before
    fun setUp() = runTest {
        application = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            application,
            Configuration.Builder()
                .setWorkerFactory((application as ai.androidclaw.app.AndroidClawApplication).container.workerFactory)
                .build(),
        )
        database = buildTestDatabase(application)
        repository = TaskRepository(database.taskDao(), database.taskRunDao())
        eventLogRepository = EventLogRepository(database.eventLogDao())
        coordinator = SchedulerCoordinator(
            application = application,
            clock = Clock.fixed(Instant.parse("2026-03-09T00:00:00Z"), ZoneOffset.UTC),
            taskRepository = repository,
            eventLogRepository = eventLogRepository,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `rescheduleAll restores persisted next work for enabled tasks`() = runTest {
        val task = repository.createTask(
            name = "Daily check",
            prompt = "Check status",
            schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
            executionMode = TaskExecutionMode.MainSession,
            targetSessionId = null,
        )

        coordinator.rescheduleAll()

        val workInfos = WorkManager.getInstance(application)
            .getWorkInfosForUniqueWork(SchedulerCoordinator.nextWorkName(task.id))
            .get()

        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos.single().state)
    }
}
