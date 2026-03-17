package ai.androidclaw.runtime.skills

import android.content.res.AssetManager
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.runtime.tools.ToolAvailability
import ai.androidclaw.runtime.tools.ToolAvailabilityStatus
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.runtime.tools.ToolPermissionRequirement
import ai.androidclaw.testutil.InMemorySkillConfigStore
import ai.androidclaw.testutil.InMemorySkillSecretStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkillManagerTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var skillRepository: SkillRepository

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        database = buildTestDatabase(application)
        skillRepository = SkillRepository(database.skillRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `refreshBundledSkills caches results until force refresh`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val firstBatch = listOf(skillSnapshot(id = "first", name = "first"))
        val secondBatch = listOf(skillSnapshot(id = "second", name = "second"))
        val loader = CountingBundledSkillLoader(
            assetManager = application.assets,
            batches = ArrayDeque<List<SkillSnapshot>>().apply {
                add(firstBatch)
                add(secondBatch)
            },
        )
        val manager = createTestSkillManager(
            application = application,
            skillRepository = skillRepository,
            bundledSkillLoader = loader,
            toolDescriptor = { name ->
                ToolDescriptor(
                    name = name,
                    description = "Tool $name",
                )
            },
        )

        val initial = manager.refreshBundledSkills()
        val cached = manager.refreshBundledSkills()
        val refreshed = manager.refreshBundledSkills(forceRefresh = true)

        assertEquals(2, loader.loadCount)
        assertEquals(initial.map { it.id }, cached.map { it.id })
        assertEquals(listOf("second"), refreshed.map { it.id })
    }

    @Test
    fun `cached refresh overlays enabled state from repository`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val loader = CountingBundledSkillLoader(
            assetManager = application.assets,
            batches = ArrayDeque<List<SkillSnapshot>>().apply {
                add(listOf(skillSnapshot(id = "toggle", name = "toggle")))
            },
        )
        val manager = createTestSkillManager(
            application = application,
            skillRepository = skillRepository,
            bundledSkillLoader = loader,
            toolDescriptor = { name ->
                ToolDescriptor(
                    name = name,
                    description = "Tool $name",
                )
            },
        )

        val initial = manager.refreshBundledSkills()
        skillRepository.setEnabled(id = "toggle", enabled = false)
        val cached = manager.refreshBundledSkills()

        assertTrue(initial.single().enabled)
        assertEquals(1, loader.loadCount)
        assertEquals(false, cached.single().enabled)
    }

    @Test
    fun `refreshBundledSkills applies missing tool eligibility`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val loader = CountingBundledSkillLoader(
            assetManager = application.assets,
            batches = ArrayDeque<List<SkillSnapshot>>().apply {
                add(
                    listOf(
                        skillSnapshot(
                            id = "list-tasks",
                            name = "list_tasks",
                            commandDispatch = SkillCommandDispatch.Tool,
                            commandTool = "tasks.list",
                        ),
                    ),
                )
            },
        )
        val manager = createTestSkillManager(
            application = application,
            skillRepository = skillRepository,
            bundledSkillLoader = loader,
            toolDescriptor = { null },
        )

        val skills = manager.refreshBundledSkills()

        assertEquals(SkillEligibilityStatus.MissingTool, skills.single().eligibility.status)
        assertTrue(skills.single().eligibility.reasons.single().contains("tasks.list"))
    }

    @Test
    fun `refreshBundledSkills applies blocked tool eligibility reasons`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val loader = CountingBundledSkillLoader(
            assetManager = application.assets,
            batches = ArrayDeque<List<SkillSnapshot>>().apply {
                add(
                    listOf(
                        skillSnapshot(
                            id = "notify",
                            name = "notify",
                            commandDispatch = SkillCommandDispatch.Tool,
                            commandTool = "notifications.post",
                        ),
                    ),
                )
            },
        )
        val manager = createTestSkillManager(
            application = application,
            skillRepository = skillRepository,
            bundledSkillLoader = loader,
            toolDescriptor = { name ->
                ToolDescriptor(
                    name = name,
                    description = "Tool $name",
                    requiredPermissions = listOf(
                        ToolPermissionRequirement(
                            permission = "android.permission.POST_NOTIFICATIONS",
                            displayName = "Post notifications",
                        ),
                    ),
                    availability = ToolAvailability(
                        status = ToolAvailabilityStatus.PermissionRequired,
                        reason = "Grant notification permission.",
                    ),
                )
            },
        )

        val skills = manager.refreshBundledSkills()

        assertEquals(SkillEligibilityStatus.MissingTool, skills.single().eligibility.status)
        assertTrue(skills.single().eligibility.reasons.single().contains("Tool blocked: notifications.post"))
        assertTrue(skills.single().eligibility.reasons.single().contains("Post notifications"))
    }

    @Test
    fun `required env becomes eligible after skill secret is configured`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val secretStore = InMemorySkillSecretStore()
        val manager = createTestSkillManager(
            application = application,
            skillRepository = skillRepository,
            bundledSkillLoader = staticLoader(
                skillSnapshot(
                    id = "env-skill",
                    name = "env_skill",
                    metadata = openClawMetadata(requiredEnv = listOf("X_API_KEY")),
                ),
            ),
            toolDescriptor = { name -> ToolDescriptor(name = name, description = name) },
            skillSecretStore = secretStore,
        )

        val missing = manager.refreshBundledSkills(forceRefresh = true).single()
        assertEquals(SkillEligibilityStatus.MissingTool, missing.eligibility.status)
        assertEquals(false, missing.secretStatuses["X_API_KEY"])

        manager.saveSkillConfiguration(
            skillKey = missing.skillKey,
            secretUpdates = mapOf("X_API_KEY" to "secret-value"),
            configUpdates = emptyMap(),
        )

        val configured = manager.refreshBundledSkills(forceRefresh = true).single()
        assertEquals(SkillEligibilityStatus.Eligible, configured.eligibility.status)
        assertEquals(true, configured.secretStatuses["X_API_KEY"])

        manager.saveSkillConfiguration(
            skillKey = configured.skillKey,
            secretUpdates = mapOf("X_API_KEY" to null),
            configUpdates = emptyMap(),
        )

        val cleared = manager.refreshBundledSkills(forceRefresh = true).single()
        assertEquals(SkillEligibilityStatus.MissingTool, cleared.eligibility.status)
        assertEquals(false, cleared.secretStatuses["X_API_KEY"])
    }

    @Test
    fun `required config path becomes eligible after value is stored`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val configStore = InMemorySkillConfigStore()
        val manager = createTestSkillManager(
            application = application,
            skillRepository = skillRepository,
            bundledSkillLoader = staticLoader(
                skillSnapshot(
                    id = "config-skill",
                    name = "config_skill",
                    metadata = openClawMetadata(requiredConfig = listOf("calendar.accountId")),
                ),
            ),
            toolDescriptor = { name -> ToolDescriptor(name = name, description = name) },
            skillConfigStore = configStore,
        )

        val missing = manager.refreshBundledSkills(forceRefresh = true).single()
        assertEquals(SkillEligibilityStatus.MissingTool, missing.eligibility.status)
        assertEquals(false, missing.configStatuses["calendar.accountId"])

        manager.saveSkillConfiguration(
            skillKey = missing.skillKey,
            secretUpdates = emptyMap(),
            configUpdates = mapOf("calendar.accountId" to "primary"),
        )

        val configured = manager.refreshBundledSkills(forceRefresh = true).single()
        assertEquals(SkillEligibilityStatus.Eligible, configured.eligibility.status)
        assertEquals(true, configured.configStatuses["calendar.accountId"])
    }

    @Test
    fun `readSkillConfiguration surfaces recovered secret notice`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val secretStore = InMemorySkillSecretStore(
            initialRecoveryNotices = setOf("recoverable_skill" to "X_API_KEY"),
        )
        val manager = createTestSkillManager(
            application = application,
            skillRepository = skillRepository,
            bundledSkillLoader = staticLoader(
                skillSnapshot(
                    id = "recoverable-skill",
                    name = "recoverable_skill",
                    metadata = openClawMetadata(requiredEnv = listOf("X_API_KEY")),
                ),
            ),
            toolDescriptor = { name -> ToolDescriptor(name = name, description = name) },
            skillSecretStore = secretStore,
        )

        val skill = manager.refreshBundledSkills(forceRefresh = true).single()
        val configuration = manager.readSkillConfiguration(skill)

        assertEquals(
            "Saved secret X_API_KEY could not be restored on this device. Please enter it again.",
            configuration.recoveryMessage,
        )
        assertFalse(configuration.secretFields.single().configured)
    }

    @Test
    fun `readSkillConfiguration exposes primary env and stored config state`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val configStore = InMemorySkillConfigStore(
            initialValues = mapOf(("config_skill" to "calendar.accountId") to "primary"),
        )
        val secretStore = InMemorySkillSecretStore(
            initialValues = mapOf(("config_skill" to "X_API_KEY") to "secret-value"),
        )
        val manager = createTestSkillManager(
            application = application,
            skillRepository = skillRepository,
            bundledSkillLoader = staticLoader(
                skillSnapshot(
                    id = "config-skill",
                    name = "config_skill",
                    metadata = openClawMetadata(
                        primaryEnv = "X_API_KEY",
                        requiredConfig = listOf("calendar.accountId"),
                    ),
                ),
            ),
            toolDescriptor = { name -> ToolDescriptor(name = name, description = name) },
            skillConfigStore = configStore,
            skillSecretStore = secretStore,
        )

        val skill = manager.refreshBundledSkills(forceRefresh = true).single()
        val configuration = manager.readSkillConfiguration(skill)

        assertEquals("config_skill", configuration.skillKey)
        assertEquals(listOf("X_API_KEY"), configuration.secretFields.map { it.envName })
        assertTrue(configuration.secretFields.single().configured)
        assertEquals("primary", configuration.configFields.single().value)
    }
}

private class CountingBundledSkillLoader(
    assetManager: AssetManager,
    private val batches: ArrayDeque<List<SkillSnapshot>>,
) : BundledSkillLoader(
    assetManager = assetManager,
    rootPath = "skills",
    parser = SkillParser(),
) {
    var loadCount: Int = 0
        private set

    override suspend fun load(): List<SkillSnapshot> {
        loadCount += 1
        return if (batches.isEmpty()) {
            emptyList()
        } else {
            batches.removeFirst()
        }
    }
}

private fun staticLoader(skill: SkillSnapshot): BundledSkillLoader {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    return CountingBundledSkillLoader(
        assetManager = application.assets,
        batches = ArrayDeque<List<SkillSnapshot>>().apply {
            repeat(6) { add(listOf(skill)) }
        },
    )
}

private fun skillSnapshot(
    id: String,
    name: String,
    commandDispatch: SkillCommandDispatch = SkillCommandDispatch.Model,
    commandTool: String? = null,
    metadata: kotlinx.serialization.json.JsonObject? = null,
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
            metadata = metadata,
            unknownFields = emptyMap(),
        ),
        instructionsMd = "Do work",
        eligibility = SkillEligibility(status = SkillEligibilityStatus.Eligible),
    )
}

private fun openClawMetadata(
    primaryEnv: String? = null,
    requiredEnv: List<String> = emptyList(),
    requiredConfig: List<String> = emptyList(),
): kotlinx.serialization.json.JsonObject {
    return buildJsonObject {
        put("openclaw", buildJsonObject {
            primaryEnv?.let { put("primaryEnv", it) }
            put("requires", buildJsonObject {
                if (requiredEnv.isNotEmpty()) {
                    putJsonArray("env") {
                        requiredEnv.forEach { add(JsonPrimitive(it)) }
                    }
                }
                if (requiredConfig.isNotEmpty()) {
                    putJsonArray("config") {
                        requiredConfig.forEach { add(JsonPrimitive(it)) }
                    }
                }
            })
        })
    }
}
