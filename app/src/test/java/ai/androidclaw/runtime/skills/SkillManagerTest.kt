package ai.androidclaw.runtime.skills

import android.content.res.AssetManager
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.runtime.tools.ToolAvailability
import ai.androidclaw.runtime.tools.ToolAvailabilityStatus
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.runtime.tools.ToolPermissionRequirement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

private fun skillSnapshot(
    id: String,
    name: String,
    commandDispatch: SkillCommandDispatch = SkillCommandDispatch.Model,
    commandTool: String? = null,
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
        eligibility = SkillEligibility(status = SkillEligibilityStatus.Eligible),
    )
}
