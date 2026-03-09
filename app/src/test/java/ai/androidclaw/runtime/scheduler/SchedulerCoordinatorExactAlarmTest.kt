package ai.androidclaw.runtime.scheduler

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.repository.TaskRepository
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class SchedulerCoordinatorExactAlarmTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: TaskRepository
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
        coordinator = SchedulerCoordinator(
            application = application,
            clock = Clock.fixed(Instant.parse("2026-03-09T00:00:00Z"), ZoneOffset.UTC),
            taskRepository = repository,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `precise task uses exact alarm when permission is granted`() = runTest {
        val alarmManager = requireNotNull(application.getSystemService(android.app.AlarmManager::class.java))
        val shadowAlarmManager = shadowOf(alarmManager)
        setCanScheduleExactAlarms(shadowAlarmManager, true)
        val task = repository.createTask(
            name = "Precise reminder",
            prompt = "Ping me exactly on time",
            schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
            executionMode = TaskExecutionMode.MainSession,
            targetSessionId = null,
            precise = true,
        )

        coordinator.scheduleTask(task.id)

        val scheduledAlarm = shadowAlarmManager.peekNextScheduledAlarm()
        val workInfos = WorkManager.getInstance(application)
            .getWorkInfosForUniqueWork(SchedulerCoordinator.nextWorkName(task.id))
            .get()

        assertNotNull(scheduledAlarm)
        assertTrue(workInfos.isEmpty())
    }

    @Test
    fun `precise task falls back to WorkManager when exact permission is denied`() = runTest {
        val alarmManager = requireNotNull(application.getSystemService(android.app.AlarmManager::class.java))
        val shadowAlarmManager = shadowOf(alarmManager)
        setCanScheduleExactAlarms(shadowAlarmManager, false)
        val task = repository.createTask(
            name = "Precise reminder",
            prompt = "Ping me exactly on time",
            schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
            executionMode = TaskExecutionMode.MainSession,
            targetSessionId = null,
            precise = true,
        )

        coordinator.scheduleTask(task.id)

        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        val workInfos = WorkManager.getInstance(application)
            .getWorkInfosForUniqueWork(SchedulerCoordinator.nextWorkName(task.id))
            .get()

        assertEquals(0, scheduledAlarms.size)
        assertEquals(1, workInfos.size)
    }
}

private fun setCanScheduleExactAlarms(shadowAlarmManager: Any, value: Boolean) {
    val method = shadowAlarmManager.javaClass.getDeclaredMethod(
        "setCanScheduleExactAlarms",
        Boolean::class.javaPrimitiveType,
    )
    method.isAccessible = true
    method.invoke(shadowAlarmManager, value)
}
