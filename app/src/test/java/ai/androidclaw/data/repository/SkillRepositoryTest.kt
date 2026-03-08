package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.SkillRecord
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillSourceType
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SkillRepositoryTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: SkillRepository

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        repository = SkillRepository(database.skillRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upsert and observe skills preserve typed frontmatter and enabled filter`() = runTest {
        val emissions = mutableListOf<List<SkillRecord>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeSkills().take(2).toList(emissions)
        }
        runCurrent()

        repository.upsertAll(
            listOf(
                skillRecord(
                    id = "summary",
                    sourceType = SkillSourceType.Bundled,
                    enabled = true,
                    displayName = "Summary",
                    description = "Bundled summary skill",
                    frontmatter = sampleFrontmatter(),
                    eligibilityStatus = SkillEligibilityStatus.Eligible,
                    eligibilityReasons = emptyList(),
                ),
                skillRecord(
                    id = "task-helper",
                    sourceType = SkillSourceType.Local,
                    enabled = false,
                    displayName = "Task helper",
                    description = "Needs task tool",
                    frontmatter = null,
                    eligibilityStatus = SkillEligibilityStatus.MissingTool,
                    eligibilityReasons = listOf("Missing tool: tasks.list"),
                ),
            ),
        )

        job.join()

        assertEquals(emptyList<SkillRecord>(), emissions.first())
        assertEquals(listOf("Summary", "Task helper"), emissions.last().map { it.displayName })
        assertEquals(listOf("summary"), repository.getEnabledSkills().map { it.id })

        repository.setEnabled("task-helper", true)
        val stored = repository.getSkill("summary")
        assertTrue(repository.getSkill("task-helper")?.enabled == true)
        assertEquals(SkillCommandDispatch.Model, stored?.frontmatter?.commandDispatch)
        assertEquals(SkillSourceType.Bundled, stored?.sourceType)
    }

    private fun sampleFrontmatter(): SkillFrontmatter {
        return SkillFrontmatter(
            name = "summary",
            description = "Summarize conversation state",
            homepage = "https://example.com/summary",
            userInvocable = true,
            disableModelInvocation = false,
            commandDispatch = SkillCommandDispatch.Model,
            commandTool = null,
            commandArgMode = "raw",
            metadata = buildJsonObject {
                put("android", "supported")
            },
            unknownFields = emptyMap(),
        )
    }

    private fun skillRecord(
        id: String,
        sourceType: SkillSourceType,
        enabled: Boolean,
        displayName: String,
        description: String,
        frontmatter: SkillFrontmatter?,
        eligibilityStatus: SkillEligibilityStatus,
        eligibilityReasons: List<String>,
    ): SkillRecord {
        return SkillRecord(
            id = id,
            sourceType = sourceType,
            enabled = enabled,
            displayName = displayName,
            description = description,
            frontmatter = frontmatter,
            eligibilityStatus = eligibilityStatus,
            eligibilityReasons = eligibilityReasons,
            importedAt = Instant.ofEpochMilli(100L),
            updatedAt = Instant.ofEpochMilli(200L),
        )
    }
}
