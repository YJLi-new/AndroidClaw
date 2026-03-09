package ai.androidclaw.feature.health

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.testutil.MainDispatcherRule
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HealthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: AndroidClawDatabase
    private lateinit var eventLogRepository: EventLogRepository
    private lateinit var application: android.app.Application
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() = runTest {
        application = ApplicationProvider.getApplicationContext()
        database = buildTestDatabase(application)
        eventLogRepository = EventLogRepository(database.eventLogDao())
        settingsDataStore = SettingsDataStore(application)
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot(
                providerType = ProviderType.OpenAiCompatible,
                openAiModelId = "gpt-test",
            ),
        )
    }

    @After
    fun tearDown() = runTest {
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        database.close()
    }

    @Test
    fun `health state includes provider tool and recent event data`() = runTest {
        eventLogRepository.log(
            category = EventCategory.System,
            level = EventLevel.Warn,
            message = "Something happened",
        )
        eventLogRepository.log(
            category = EventCategory.Scheduler,
            level = EventLevel.Info,
            message = "Task Daily check started.",
            details = "taskRunId=run-1",
        )
        eventLogRepository.log(
            category = EventCategory.Scheduler,
            level = EventLevel.Warn,
            message = "Task worker stopped.",
            details = "taskId=task-1 stopReason=quota",
        )
        eventLogRepository.log(
            category = EventCategory.Scheduler,
            level = EventLevel.Info,
            message = "Task Daily check completed.",
            details = "taskRunId=run-1",
        )
        val viewModel = HealthViewModel(
            schedulerCoordinator = SchedulerCoordinator(
                application = application,
                clock = Clock.fixed(Instant.parse("2026-03-08T00:00:00Z"), ZoneOffset.UTC),
            ),
            toolRegistry = ToolRegistry(
                tools = listOf(
                    ToolRegistry.Entry(
                        descriptor = ToolDescriptor(
                            name = "health.status",
                            description = "Health",
                        ),
                    ) { _ ->
                        ai.androidclaw.runtime.tools.ToolExecutionResult(
                            summary = "ok",
                            payload = buildJsonObject {},
                        )
                    },
                ),
            ),
            providerRegistry = buildTestProviderRegistry(),
            settingsDataStore = settingsDataStore,
            eventLogRepository = eventLogRepository,
        )

        val state = viewModel.state.first { it.recentEvents.isNotEmpty() }

        assertEquals("openai-compatible", state.providerId)
        assertEquals(listOf("health.status"), state.tools)
        assertTrue(state.lastAutomationResult?.contains("completed") == true)
        assertEquals("taskId=task-1 stopReason=quota", state.lastWorkerStopReason)
        assertNotNull(state.lastSchedulerWake)
        assertTrue(state.recentEvents.any { it.message == "Something happened" })
    }
}
