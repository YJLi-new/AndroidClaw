package ai.androidclaw.feature.health

import ai.androidclaw.app.CrashMarkerStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.providers.NetworkStatusSnapshot
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.runtime.tools.ToolExecutionContext
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolInvocationOrigin
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.testutil.MainDispatcherRule
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HealthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: AndroidClawDatabase
    private lateinit var eventLogRepository: EventLogRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var application: android.app.Application
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var crashMarkerStore: CrashMarkerStore
    private val networkStatusFlow =
        MutableStateFlow(
            NetworkStatusSnapshot(
                supported = true,
                isConnected = true,
                isValidated = true,
                isMetered = false,
            ),
        )

    @Before
    fun setUp() =
        runTest {
            application = ApplicationProvider.getApplicationContext()
            database = buildTestDatabase(application)
            eventLogRepository = EventLogRepository(database.eventLogDao())
            taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
            settingsDataStore = SettingsDataStore(application)
            crashMarkerStore = CrashMarkerStore(application)
            crashMarkerStore.clear()
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAiCompatible,
                        ai.androidclaw.data.ProviderEndpointSettings(
                            baseUrl = ProviderType.OpenAiCompatible.defaultBaseUrl,
                            modelId = "gpt-test",
                            timeoutSeconds = ProviderType.OpenAiCompatible.defaultTimeoutSeconds,
                        ),
                    ).copy(providerType = ProviderType.OpenAiCompatible),
            )
            networkStatusFlow.value =
                NetworkStatusSnapshot(
                    supported = true,
                    isConnected = true,
                    isValidated = true,
                    isMetered = false,
                )
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            database.close()
            crashMarkerStore.clear()
        }

    @Test
    fun `health state includes provider tool and recent event data`() =
        runTest {
            eventLogRepository.log(
                category = EventCategory.System,
                level = EventLevel.Warn,
                message = "Something happened",
            )
            eventLogRepository.log(
                category = EventCategory.Provider,
                level = EventLevel.Error,
                message = "Provider stream was interrupted before completion.",
                details = "kind=StreamInterrupted retryable=true",
            )
            eventLogRepository.log(
                category = EventCategory.Provider,
                level = EventLevel.Info,
                message = "Retrying failed turn.",
                details = "sessionId=session-1",
            )
            crashMarkerStore.record(
                threadName = "main",
                throwable = IllegalStateException("Boom"),
                timestamp = Instant.parse("2026-03-08T01:00:00Z"),
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
            val toolRegistry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "health.status",
                                        description = "Health",
                                    ),
                            ) { _, _ ->
                                ToolExecutionResult(
                                    summary = "ok",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                    eventLogger = { level, message, details ->
                        eventLogRepository.log(
                            category = EventCategory.Tool,
                            level = level,
                            message = message,
                            details = details,
                        )
                    },
                )
            toolRegistry.execute(
                context =
                    ToolExecutionContext(
                        sessionId = "session-1",
                        taskRunId = "run-1",
                        origin = ToolInvocationOrigin.ScheduledModel,
                        runMode = ModelRunMode.Scheduled,
                        requestedName = "health.status",
                        canonicalName = "health.status",
                        requestId = "req-1",
                        activeSkillId = "skill-1",
                    ),
                arguments = buildJsonObject {},
            )
            val viewModel =
                HealthViewModel(
                    schedulerCoordinator =
                        SchedulerCoordinator(
                            application = application,
                            clock = Clock.fixed(Instant.parse("2026-03-08T00:00:00Z"), ZoneOffset.UTC),
                            taskRepository = taskRepository,
                            eventLogRepository = eventLogRepository,
                        ),
                    toolRegistry = toolRegistry,
                    providerRegistry = buildTestProviderRegistry(),
                    networkStatusProvider = buildNetworkStatusProvider(),
                    settingsDataStore = settingsDataStore,
                    eventLogRepository = eventLogRepository,
                    crashMarkerStore = crashMarkerStore,
                )

            val state = viewModel.state.first { it.recentEvents.isNotEmpty() }

            assertEquals("openai-compatible", state.providerId)
            assertEquals("Connected", state.networkSummary)
            assertTrue(state.providerStatus.contains("Last provider issue"))
            assertTrue(state.lastProviderIssue?.contains("Stream Interrupted") == true)
            assertTrue(state.lastProviderIssue?.contains("retryable") == true)
            assertTrue(state.lastCrashSummary?.contains("IllegalStateException") == true)
            assertEquals(listOf("health.status"), state.tools)
            assertTrue(state.lastAutomationResult?.contains("completed") == true)
            assertEquals("taskId=task-1 stopReason=quota", state.lastWorkerStopReason)
            assertNotNull(state.lastSchedulerWake)
            assertTrue(state.recentEvents.any { it.message == "Something happened" })
            assertTrue(state.recentEvents.any { it.category == EventCategory.Tool && it.message.contains("completed") })
        }

    @Test
    fun `health state reacts to network status changes`() =
        runTest {
            val viewModel =
                HealthViewModel(
                    schedulerCoordinator =
                        SchedulerCoordinator(
                            application = application,
                            clock = Clock.fixed(Instant.parse("2026-03-08T00:00:00Z"), ZoneOffset.UTC),
                            taskRepository = taskRepository,
                            eventLogRepository = eventLogRepository,
                        ),
                    toolRegistry = ToolRegistry(emptyList(), eventLogger = { _, _, _ -> }),
                    providerRegistry = buildTestProviderRegistry(),
                    networkStatusProvider = buildNetworkStatusProvider(),
                    settingsDataStore = settingsDataStore,
                    eventLogRepository = eventLogRepository,
                    crashMarkerStore = crashMarkerStore,
                )

            val connected = viewModel.state.first { it.providerId == "openai-compatible" && it.networkSummary == "Connected" }
            assertEquals("Connected", connected.networkSummary)

            networkStatusFlow.value =
                NetworkStatusSnapshot(
                    supported = true,
                    isConnected = false,
                    isValidated = false,
                    isMetered = false,
                )

            val offline = viewModel.state.first { it.networkSummary == "Offline" }
            assertTrue(offline.providerStatus.contains("No active network connection"))

            networkStatusFlow.value =
                NetworkStatusSnapshot(
                    supported = true,
                    isConnected = true,
                    isValidated = false,
                    isMetered = false,
                )

            val unvalidated = viewModel.state.first { it.networkSummary == "Connected, internet not validated" }
            assertTrue(unvalidated.providerStatus.contains("not validated", ignoreCase = true))
        }

    private fun buildNetworkStatusProvider(): NetworkStatusProvider =
        object : NetworkStatusProvider {
            override fun currentStatus(): NetworkStatusSnapshot = networkStatusFlow.value

            override fun observeStatus() = networkStatusFlow
        }
}
