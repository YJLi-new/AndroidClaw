package ai.androidclaw.runtime.tools

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibility
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.skills.SkillSourceType
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BuiltInToolsTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var sessionRepository: SessionRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() = runTest {
        application = ApplicationProvider.getApplicationContext()
        database = buildTestDatabase(application)
        sessionRepository = SessionRepository(database.sessionDao())
        taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
        settingsDataStore = SettingsDataStore(application)
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
    }

    @After
    fun tearDown() = runTest {
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        database.close()
    }

    @Test
    fun `sessions list returns persisted sessions`() = runTest {
        sessionRepository.getOrCreateMainSession()
        sessionRepository.createSession("Project X")
        val registry = buildRegistry()

        val result = registry.execute(
            name = "sessions.list",
            arguments = buildJsonObject {},
        )

        assertTrue(result.success)
        assertEquals("2", result.payload["sessionCount"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("Main session", "Project X"),
            result.payload["sessions"]
                ?.jsonArray
                ?.map { it.jsonObject["title"]?.jsonPrimitive?.content.orEmpty() }
                ?.sorted(),
        )
    }

    @Test
    fun `tasks list returns capabilities and persisted task names`() = runTest {
        taskRepository.createTask(
            name = "Daily check",
            prompt = "Check health",
            schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
            executionMode = TaskExecutionMode.MainSession,
            targetSessionId = null,
        )
        val registry = buildRegistry()

        val result = registry.execute(
            name = "task.list",
            arguments = buildJsonObject {},
        )

        assertTrue(result.success)
        assertEquals("1", result.payload["taskCount"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("Daily check"),
            result.payload["taskNames"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals("true", result.payload["supportsCron"]?.jsonPrimitive?.content)
    }

    @Test
    fun `skills list returns eligibility metadata`() = runTest {
        val registry = buildRegistry(
            bundledSkills = listOf(
                skillSnapshot(
                    id = "notify",
                    name = "notify",
                    commandDispatch = SkillCommandDispatch.Tool,
                    commandTool = "notifications.post",
                    eligibility = SkillEligibility(
                        status = SkillEligibilityStatus.MissingTool,
                        reasons = listOf("Tool blocked: notifications.post (Post notifications)"),
                    ),
                ),
            ),
        )

        val result = registry.execute(
            name = "skills.list",
            arguments = buildJsonObject {},
        )

        assertTrue(result.success)
        assertEquals("1", result.payload["skillCount"]?.jsonPrimitive?.content)
        val skill = result.payload["skills"]?.jsonArray?.single()?.jsonObject
        assertEquals("notify", skill?.get("name")?.jsonPrimitive?.content)
        assertEquals("MissingTool", skill?.get("eligibilityStatus")?.jsonPrimitive?.content)
        assertEquals(
            listOf("Tool blocked: notifications.post (Post notifications)"),
            skill?.get("eligibilityReasons")?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `health status reports selected provider and current tool availability`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot(
                providerType = ProviderType.OpenAiCompatible,
            ),
        )
        val registry = buildRegistry()

        val result = registry.execute(
            name = "health.status",
            arguments = buildJsonObject {},
        )

        assertTrue(result.success)
        assertEquals("openai-compatible", result.payload["provider"]?.jsonPrimitive?.content)
        val tools = result.payload["tools"]?.jsonArray.orEmpty()
        assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "notifications.post" })
        val notificationsTool = tools.first { it.jsonObject["name"]?.jsonPrimitive?.content == "notifications.post" }
        assertEquals(
            notificationToolAvailability(application).status.name,
            notificationsTool.jsonObject["availabilityStatus"]?.jsonPrimitive?.content,
        )
    }

    private fun buildRegistry(
        bundledSkills: List<SkillSnapshot> = emptyList(),
    ): ToolRegistry {
        return createBuiltInToolRegistry(
            application = application,
            settingsDataStore = settingsDataStore,
            sessionRepository = sessionRepository,
            taskRepository = taskRepository,
            bundledSkillsProvider = { bundledSkills },
        )
    }
}

private fun skillSnapshot(
    id: String,
    name: String,
    commandDispatch: SkillCommandDispatch = SkillCommandDispatch.Model,
    commandTool: String? = null,
    eligibility: SkillEligibility = SkillEligibility(SkillEligibilityStatus.Eligible),
): SkillSnapshot {
    return SkillSnapshot(
        id = id,
        skillKey = name,
        sourceType = SkillSourceType.Bundled,
        baseDir = "asset://skills/$id",
        enabled = true,
        frontmatter = SkillFrontmatter(
            name = name,
            description = "Description for $name",
            homepage = null,
            userInvocable = true,
            disableModelInvocation = false,
            commandDispatch = commandDispatch,
            commandTool = commandTool,
            commandArgMode = "raw",
            metadata = null,
            unknownFields = emptyMap(),
        ),
        instructionsMd = "Do work",
        eligibility = eligibility,
    )
}
