package ai.androidclaw.runtime.scheduler

import ai.androidclaw.app.AndroidClawApplication
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ExactAlarmRegressionTest {
    private lateinit var application: AndroidClawApplication

    @Before
    fun setUp() =
        runBlocking {
            application =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .applicationContext as AndroidClawApplication
            withContext(Dispatchers.IO) {
                application.container.database.clearAllTables()
            }
            application.container.settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            application.container.ensureMainSession()
        }

    @After
    fun tearDown() =
        runBlocking {
            withContext(Dispatchers.IO) {
                application.container.database.clearAllTables()
            }
            application.container.settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        }

    @Test
    fun preciseTaskMatchesExpectedExactAlarmPermissionState() =
        runBlocking {
            assumeTrue(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            val args = InstrumentationRegistry.getArguments()
            val expectedGranted =
                args
                    .getString("expectedExactAlarmGranted")
                    ?.toBooleanStrictOrNull()
                    ?: error("expectedExactAlarmGranted instrumentation arg is required.")
            val verifyShellAlarm =
                args
                    .getString("verifyShellAlarm")
                    ?.toBooleanStrictOrNull()
                    ?: false

            val mainSession = application.container.sessionRepository.getOrCreateMainSession()
            val task =
                application.container.taskRepository.createTask(
                    name = "Exact alarm regression",
                    prompt = "Summarize the session",
                    schedule = TaskSchedule.Once(Instant.now().plusSeconds(600)),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = mainSession.id,
                    precise = true,
                )

            val diagnostics = application.container.schedulerCoordinator.diagnostics()
            assertEquals(expectedGranted, diagnostics.exactAlarmGranted)

            application.container.schedulerCoordinator.scheduleTask(task.id)

            val workInfos =
                WorkManager
                    .getInstance(application)
                    .getWorkInfosForUniqueWork(SchedulerCoordinator.nextWorkName(task.id))
                    .get()
            val events =
                application.container.eventLogRepository
                    .observeRecent(limit = 20)
                    .first()

            if (expectedGranted) {
                assertTrue(workInfos.isEmpty())
                assertTrue(
                    events.any { event ->
                        event.category == EventCategory.Scheduler &&
                            event.level == EventLevel.Info &&
                            event.message == "Scheduled exact alarm for task ${task.name}."
                    },
                )
                if (verifyShellAlarm) {
                    val dump = executeShellCommand("dumpsys alarm")
                    assertTrue(dump.contains("ai.androidclaw.app"))
                }
            } else {
                assertEquals(1, workInfos.size)
                assertEquals(WorkInfo.State.ENQUEUED, workInfos.single().state)
                assertTrue(
                    events.any { event ->
                        event.category == EventCategory.Scheduler &&
                            event.level == EventLevel.Warn &&
                            event.message == "Exact alarm degraded for task ${task.name}." &&
                            event.details?.contains("degradedReason=") == true
                    },
                )
            }
        }

    private fun executeShellCommand(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        FileInputStream(descriptor.fileDescriptor).use { stream ->
            return stream.bufferedReader().readText()
        }
    }
}
