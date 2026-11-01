package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.orchestrator.toBoundedModelToolDescriptors
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ToolPayloadBudgetTest {
    private val testClock = Clock.fixed(Instant.parse("2026-03-08T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(application, Configuration.Builder().build())
        database = buildTestDatabase(application)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `interactive tool payload stays within the token budget`() =
        runTest {
            val registry = buildRegistry()
            val all = registry.descriptors()
            val interactive = registry.descriptorsFor(ToolInvocationOrigin.Model, ModelRunMode.Interactive)
            val scheduled = registry.descriptorsFor(ToolInvocationOrigin.ScheduledModel, ModelRunMode.Scheduled)
            val sent = interactive.toBoundedModelToolDescriptors()
            val bytes = sent.toOpenAiToolsJson().toByteArray(Charsets.UTF_8).size
            val maxDescription = sent.maxOfOrNull { it.description.length } ?: 0
            println(
                "TOOL_MEASURE registered=${all.size} interactive=${interactive.size} scheduled=${scheduled.size} " +
                    "sent=${sent.size} openAiToolBytes=$bytes maxDescriptionChars=$maxDescription",
            )

            assertTrue("registered=${all.size}", all.size <= 45)
            assertTrue("interactive=${interactive.size}", interactive.size <= 40)
            assertEquals("every model-facing tool is sent", interactive.size, sent.size)
            assertTrue("openAiToolBytes=$bytes", bytes <= 24 * 1024)
            interactive.forEach { descriptor ->
                assertTrue("${descriptor.name} description too long", descriptor.description.length <= 300)
                assertTrue("${descriptor.name} is not provider safe", PROVIDER_SAFE_NAME.matches(descriptor.name))
            }
        }

    @Test
    fun `scheduled runs never see foreground-only tools`() =
        runTest {
            val scheduled =
                buildRegistry()
                    .descriptorsFor(ToolInvocationOrigin.ScheduledModel, ModelRunMode.Scheduled)
                    .map { it.name }
                    .toSet()

            assertTrue(scheduled.containsAll(setOf("web_fetch", "web_search", "device_status", "notify", "tasks_list", "workspace_edit")))
            assertTrue(scheduled.none { it in setOf("open_url", "open_app", "set_alarm", "clipboard_get", "tasks_delete") })
        }

    @Test
    fun `slash command helpers are hidden from the model`() =
        runTest {
            val registry = buildRegistry()
            val modelNames = registry.descriptorsFor(ToolInvocationOrigin.Model, ModelRunMode.Interactive).map { it.name }
            val slashNames = registry.descriptorsFor(ToolInvocationOrigin.SlashCommand, ModelRunMode.Interactive).map { it.name }

            assertTrue("sessions_compact" !in modelNames && "memory_command" !in modelNames)
            assertTrue("sessions_compact" in slashNames && "memory_command" in slashNames)
            assertEquals("sessions_compact", registry.canonicalName("sessions.compact"))
            assertEquals("tasks_list", registry.canonicalName("tasks.list"))
        }

    private fun buildRegistry(): ToolRegistry {
        val settingsDataStore = SettingsDataStore(application)
        val taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
        val eventLogRepository = EventLogRepository(database.eventLogDao())
        return createBuiltInToolRegistry(
            application = application,
            settingsDataStore = settingsDataStore,
            sessionRepository = SessionRepository(database.sessionDao()),
            taskRepository = taskRepository,
            schedulerCoordinator =
                SchedulerCoordinator(
                    application = application,
                    clock = testClock,
                    taskRepository = taskRepository,
                    eventLogRepository = eventLogRepository,
                ),
            bundledSkillsProvider = { emptyList() },
            messageRepository = MessageRepository(database.messageDao()),
            memoryRepository = MemoryRepository(database.memoryItemDao()),
            eventLogRepository = eventLogRepository,
            clock = testClock,
        )
    }
}

private val PROVIDER_SAFE_NAME = Regex("^[A-Za-z0-9_-]{1,64}$")

/** Mirrors the `tools` array that OpenAiCompatibleProvider serializes for chat completions. */
internal fun List<ToolDescriptor>.toOpenAiToolsJson(): String =
    buildJsonArray {
        forEach { descriptor ->
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", descriptor.name)
                            put("description", descriptor.description)
                            put("parameters", descriptor.inputSchema)
                        },
                    )
                },
            )
        }
    }.toString()
