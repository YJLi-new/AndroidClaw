package ai.androidclaw.feature.skills

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.runtime.skills.BundledSkillLoader
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibility
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillParser
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.skills.SkillSourceType
import ai.androidclaw.runtime.skills.createTestSkillManager
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.testutil.InMemorySkillConfigStore
import ai.androidclaw.testutil.InMemorySkillSecretStore
import ai.androidclaw.testutil.MainDispatcherRule
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SkillsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var skillRepository: SkillRepository

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        database = buildTestDatabase(application)
        skillRepository = SkillRepository(database.skillRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `openConfiguration loads stored secret and config state`() =
        runTest {
            val viewModel =
                SkillsViewModel(
                    skillManager =
                        createSkillManager(
                            skill = configSkill(),
                            configStore =
                                InMemorySkillConfigStore(
                                    initialValues = mapOf(("config_skill" to "calendar.accountId") to "primary"),
                                ),
                            secretStore =
                                InMemorySkillSecretStore(
                                    initialValues = mapOf(("config_skill" to "X_API_KEY") to "secret-value"),
                                ),
                        ),
                )

            val loaded = waitForState(viewModel) { !it.loading && it.skills.size == 1 }

            viewModel.openConfiguration(loaded.skills.single())
            val configured = waitForState(viewModel) { it.configurationDialog?.loading == false }
            val dialog = configured.configurationDialog!!

            assertEquals("config_skill", dialog.skillKey)
            assertEquals(listOf("X_API_KEY"), dialog.secretFields.map { it.envName })
            assertTrue(dialog.secretFields.single().configured)
            assertEquals("", dialog.secretFields.single().draftValue)
            assertEquals("calendar.accountId", dialog.configFields.single().path)
            assertEquals("primary", dialog.configFields.single().storedValue)
            assertEquals("primary", dialog.configFields.single().draftValue)
        }

    @Test
    fun `saveConfiguration refreshes inline statuses and clears secret drafts`() =
        runTest {
            val viewModel =
                SkillsViewModel(
                    skillManager =
                        createSkillManager(
                            skill = configSkill(),
                            configStore = InMemorySkillConfigStore(),
                            secretStore = InMemorySkillSecretStore(),
                        ),
                )

            val loaded = waitForState(viewModel) { !it.loading && it.skills.size == 1 }
            assertEquals(
                SkillEligibilityStatus.MissingTool,
                loaded.skills
                    .single()
                    .eligibility.status,
            )
            assertEquals(false, loaded.skills.single().secretStatuses["X_API_KEY"])
            assertEquals(false, loaded.skills.single().configStatuses["calendar.accountId"])

            viewModel.openConfiguration(loaded.skills.single())
            waitForState(viewModel) { it.configurationDialog?.loading == false }

            viewModel.updateSecretDraft("X_API_KEY", "secret-value")
            viewModel.updateConfigDraft("calendar.accountId", "primary")
            viewModel.saveConfiguration()
            val saved =
                waitForState(viewModel) { state ->
                    val skill = state.skills.singleOrNull() ?: return@waitForState false
                    val dialog = state.configurationDialog ?: return@waitForState false
                    !dialog.saving &&
                        skill.eligibility.status == SkillEligibilityStatus.Eligible &&
                        skill.secretStatuses["X_API_KEY"] == true &&
                        skill.configStatuses["calendar.accountId"] == true
                }
            val skill = saved.skills.single()
            val dialog = saved.configurationDialog!!

            assertEquals(SkillEligibilityStatus.Eligible, skill.eligibility.status)
            assertEquals(true, skill.secretStatuses["X_API_KEY"])
            assertEquals(true, skill.configStatuses["calendar.accountId"])
            assertTrue(dialog.secretFields.single().configured)
            assertEquals("", dialog.secretFields.single().draftValue)
            assertFalse(dialog.secretFields.single().clearRequested)
            assertEquals("primary", dialog.configFields.single().storedValue)
            assertEquals("primary", dialog.configFields.single().draftValue)
            assertEquals("Saved configuration for config_skill.", saved.statusMessage)
        }

    @Test
    fun `clearConfiguration removes stored values and refreshes eligibility`() =
        runTest {
            val viewModel =
                SkillsViewModel(
                    skillManager =
                        createSkillManager(
                            skill = configSkill(),
                            configStore =
                                InMemorySkillConfigStore(
                                    initialValues = mapOf(("config_skill" to "calendar.accountId") to "primary"),
                                ),
                            secretStore =
                                InMemorySkillSecretStore(
                                    initialValues = mapOf(("config_skill" to "X_API_KEY") to "secret-value"),
                                ),
                        ),
                )

            val loaded = waitForState(viewModel) { !it.loading && it.skills.size == 1 }
            assertEquals(
                SkillEligibilityStatus.Eligible,
                loaded.skills
                    .single()
                    .eligibility.status,
            )

            viewModel.openConfiguration(loaded.skills.single())
            waitForState(viewModel) { it.configurationDialog?.loading == false }

            viewModel.requestSecretClear("X_API_KEY")
            viewModel.requestConfigClear("calendar.accountId")
            viewModel.saveConfiguration()
            val cleared =
                waitForState(viewModel) { state ->
                    val skill = state.skills.singleOrNull() ?: return@waitForState false
                    val dialog = state.configurationDialog ?: return@waitForState false
                    !dialog.saving &&
                        skill.eligibility.status == SkillEligibilityStatus.MissingTool &&
                        skill.secretStatuses["X_API_KEY"] == false &&
                        skill.configStatuses["calendar.accountId"] == false
                }
            val skill = cleared.skills.single()
            val dialog = cleared.configurationDialog!!

            assertEquals(SkillEligibilityStatus.MissingTool, skill.eligibility.status)
            assertEquals(false, skill.secretStatuses["X_API_KEY"])
            assertEquals(false, skill.configStatuses["calendar.accountId"])
            assertFalse(dialog.secretFields.single().configured)
            assertEquals("", dialog.secretFields.single().draftValue)
            assertFalse(dialog.secretFields.single().clearRequested)
            assertEquals(null, dialog.configFields.single().storedValue)
            assertEquals("", dialog.configFields.single().draftValue)
            assertFalse(dialog.configFields.single().clearRequested)
        }

    private fun createSkillManager(
        skill: SkillSnapshot,
        configStore: InMemorySkillConfigStore,
        secretStore: InMemorySkillSecretStore,
    ): SkillManager =
        createTestSkillManager(
            application = application,
            skillRepository = skillRepository,
            bundledSkillLoader = staticLoader(skill),
            toolDescriptor = { name -> ToolDescriptor(name = name, description = name) },
            skillConfigStore = configStore,
            skillSecretStore = secretStore,
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.waitForState(
    viewModel: SkillsViewModel,
    predicate: (SkillsUiState) -> Boolean,
): SkillsUiState {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    var lastState = viewModel.state.value
    while (System.nanoTime() < deadline) {
        advanceUntilIdle()
        val state = viewModel.state.value
        lastState = state
        if (predicate(state)) {
            return state
        }
        Thread.sleep(10)
    }
    error("Timed out waiting for state. Last state=$lastState")
}

private fun staticLoader(skill: SkillSnapshot): BundledSkillLoader {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    return CountingBundledSkillLoader(
        assetManager = application.assets,
        batches =
            ArrayDeque<List<SkillSnapshot>>().apply {
                repeat(6) { add(listOf(skill)) }
            },
    )
}

private class CountingBundledSkillLoader(
    assetManager: AssetManager,
    private val batches: ArrayDeque<List<SkillSnapshot>>,
) : BundledSkillLoader(
        assetManager = assetManager,
        rootPath = "skills",
        parser = SkillParser(),
    ) {
    override suspend fun load(): List<SkillSnapshot> =
        if (batches.isEmpty()) {
            emptyList()
        } else {
            batches.removeFirst()
        }
}

private fun configSkill(): SkillSnapshot =
    SkillSnapshot(
        id = "config-skill",
        skillKey = "config_skill",
        sourceType = SkillSourceType.Bundled,
        baseDir = "asset://skills/config-skill",
        enabled = true,
        frontmatter =
            SkillFrontmatter(
                name = "config_skill",
                description = "Skill with config",
                homepage = null,
                userInvocable = true,
                disableModelInvocation = false,
                commandDispatch = SkillCommandDispatch.Model,
                commandTool = null,
                commandArgMode = "raw",
                metadata =
                    openClawMetadata(
                        primaryEnv = "X_API_KEY",
                        requiredEnv = listOf("X_API_KEY"),
                        requiredConfig = listOf("calendar.accountId"),
                    ),
                unknownFields = emptyMap(),
            ),
        instructionsMd = "Do work",
        eligibility = SkillEligibility(status = SkillEligibilityStatus.Eligible),
    )

private fun openClawMetadata(
    primaryEnv: String? = null,
    requiredEnv: List<String> = emptyList(),
    requiredConfig: List<String> = emptyList(),
): JsonObject =
    buildJsonObject {
        put(
            "openclaw",
            buildJsonObject {
                primaryEnv?.let { put("primaryEnv", it) }
                put(
                    "requires",
                    buildJsonObject {
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
                    },
                )
            },
        )
    }
