package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.runtime.skills.BundledSkillLoader
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibility
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillParser
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.skills.SkillSourceType
import ai.androidclaw.runtime.tools.ToolAvailability
import ai.androidclaw.runtime.tools.ToolAvailabilityStatus
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolPermissionRequirement
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.testutil.buildTestProviderRegistry
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentRunnerTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var messageRepository: MessageRepository
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() = runTest {
        application = ApplicationProvider.getApplicationContext()
        database = buildTestDatabase(application)
        messageRepository = MessageRepository(database.messageDao())
        settingsDataStore = SettingsDataStore(application)
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
    }

    @After
    fun tearDown() = runTest {
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        database.close()
    }

    @Test
    fun `slash tool skill executes direct tool path without provider`() = runTest {
        val toolRegistry = ToolRegistry(
            tools = listOf(
                ToolRegistry.Entry(
                    descriptor = ToolDescriptor(
                        name = "tasks.list",
                        description = "List tasks",
                    ),
                ) { arguments ->
                    ToolExecutionResult.success(
                        summary = "Tasks tool reached",
                        payload = buildJsonObject {
                            put("command", arguments["command"]?.jsonPrimitive?.content.orEmpty())
                        },
                    )
                },
            ),
        )
        val skillManager = buildSkillManager(toolRegistry)
        val runner = AgentRunner(
            providerRegistry = buildTestProviderRegistry(
                fakeProvider = failOnGenerateProvider(),
            ),
            settingsDataStore = settingsDataStore,
            messageRepository = messageRepository,
            skillManager = skillManager,
            toolRegistry = toolRegistry,
        )

        val result = runner.runInteractiveTurn(
            AgentTurnRequest(
                sessionId = "session-1",
                userMessage = "/list_tasks pending",
            ),
        )

        assertEquals("Tasks tool reached", result.assistantMessage)
        assertEquals(listOf("list_tasks"), result.selectedSkills.map { it.displayName })
        assertNotNull(result.directToolResult)
        assertEquals("pending", result.directToolResult?.payload?.get("command")?.jsonPrimitive?.content)
        assertNull(result.providerRequestId)
    }

    @Test
    fun `blocked slash skill returns eligibility reason instead of falling through to provider`() = runTest {
        val toolRegistry = ToolRegistry(
            tools = listOf(
                ToolRegistry.Entry(
                    descriptor = ToolDescriptor(
                        name = "tasks.list",
                        description = "List tasks",
                        requiredPermissions = listOf(
                            ToolPermissionRequirement(
                                permission = "android.permission.POST_NOTIFICATIONS",
                                displayName = "Task access",
                            ),
                        ),
                    ),
                    availabilityProvider = {
                        ToolAvailability(
                            status = ToolAvailabilityStatus.PermissionRequired,
                            reason = "Grant task access.",
                        )
                    },
                ) { _ ->
                    ToolExecutionResult.success(
                        summary = "should not run",
                        payload = buildJsonObject {},
                    )
                },
            ),
        )
        val skillManager = buildSkillManager(toolRegistry)
        val runner = AgentRunner(
            providerRegistry = buildTestProviderRegistry(
                fakeProvider = failOnGenerateProvider(),
            ),
            settingsDataStore = settingsDataStore,
            messageRepository = messageRepository,
            skillManager = skillManager,
            toolRegistry = toolRegistry,
        )

        val result = runner.runInteractiveTurn(
            AgentTurnRequest(
                sessionId = "session-1",
                userMessage = "/list_tasks",
            ),
        )

        assertTrue(result.assistantMessage.contains("Skill /list_tasks is unavailable."))
        assertTrue(result.assistantMessage.contains("Tool blocked: tasks.list"))
        assertEquals(listOf("list_tasks"), result.selectedSkills.map { it.displayName })
        assertNull(result.directToolResult)
        assertNull(result.providerRequestId)
    }

    private fun buildSkillManager(toolRegistry: ToolRegistry): SkillManager {
        return SkillManager(
            bundledSkillLoader = StaticBundledSkillLoader(
                assetManager = application.assets,
                skills = listOf(
                    skillSnapshot(
                        id = "list_tasks",
                        name = "list_tasks",
                        commandDispatch = SkillCommandDispatch.Tool,
                        commandTool = "tasks.list",
                    ),
                ),
            ),
            toolDescriptor = toolRegistry::findDescriptor,
        )
    }
}

private fun failOnGenerateProvider(): ModelProvider {
    return object : ModelProvider {
        override val id: String = "fake"

        override suspend fun generate(request: ModelRequest): ModelResponse {
            error("Provider should not have been called.")
        }
    }
}

private class StaticBundledSkillLoader(
    assetManager: AssetManager,
    private val skills: List<SkillSnapshot>,
) : BundledSkillLoader(
    assetManager = assetManager,
    rootPath = "skills",
    parser = SkillParser(),
) {
    override suspend fun load(): List<SkillSnapshot> = skills
}

private fun skillSnapshot(
    id: String,
    name: String,
    commandDispatch: SkillCommandDispatch,
    commandTool: String,
): SkillSnapshot {
    return SkillSnapshot(
        id = id,
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
        instructionsMd = "Use the tool directly.",
        eligibility = SkillEligibility(
            status = SkillEligibilityStatus.Eligible,
        ),
    )
}
