package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.SkillRecordEntity
import ai.androidclaw.data.model.SkillRecord
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillSourceType
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

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
    fun `upsert and observe skills preserve typed frontmatter and enabled filter`() =
        runTest {
            val emissions = mutableListOf<List<SkillRecord>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
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

    @Test
    fun `upsert bounds skill text before persistence`() =
        runTest {
            val longSkillKey = "k".repeat(SKILL_KEY_MAX_CHARS + 25)
            val longBaseDir = "/skills/" + "b".repeat(SKILL_BASE_DIR_MAX_CHARS + 25)
            val longName = "n".repeat(SKILL_DISPLAY_NAME_MAX_CHARS + 25)
            val longDescription = "d".repeat(SKILL_DESCRIPTION_MAX_CHARS + 25)
            val longInstructions = "i".repeat(SKILL_INSTRUCTIONS_MAX_CHARS + 25)
            val longParseError = "p".repeat(SKILL_PARSE_ERROR_MAX_CHARS + 25)
            val longReason = "r".repeat(SKILL_ELIGIBILITY_REASON_MAX_CHARS + 25)
            val reasons =
                listOf(longReason, " first reason ", "first reason", " ") +
                    (1..(SKILL_ELIGIBILITY_REASONS_MAX_COUNT + 5)).map { "reason-$it" }
            val frontmatter =
                sampleFrontmatter().copy(
                    name = longName,
                    description = longDescription,
                    homepage = longBaseDir,
                    commandTool = longSkillKey,
                    commandArgMode = longSkillKey,
                )

            repository.upsertSkill(
                skillRecord(
                    id = "bounded-skill",
                    skillKey = longSkillKey,
                    sourceType = SkillSourceType.Local,
                    enabled = true,
                    displayName = longName,
                    description = longDescription,
                    frontmatter = frontmatter,
                    eligibilityStatus = SkillEligibilityStatus.MissingTool,
                    eligibilityReasons = reasons,
                    baseDir = longBaseDir,
                    instructionsMd = longInstructions,
                    parseError = longParseError,
                ),
            )
            val raw = requireNotNull(database.skillRecordDao().getById("bounded-skill"))
            val stored = requireNotNull(repository.getSkill("bounded-skill"))
            val rawFrontmatter = Json.decodeFromString(SkillFrontmatter.serializer(), requireNotNull(raw.frontmatterJson))
            val rawReasons = Json.decodeFromString(ListSerializer(String.serializer()), raw.eligibilityReasons)
            val expectedReasons = expectedBoundedReasons(reasons)

            assertEquals(longSkillKey.take(SKILL_KEY_MAX_CHARS), raw.skillKey)
            assertEquals(longBaseDir.take(SKILL_BASE_DIR_MAX_CHARS), raw.baseDir)
            assertEquals(longName.take(SKILL_DISPLAY_NAME_MAX_CHARS), raw.displayName)
            assertEquals(longDescription.take(SKILL_DESCRIPTION_MAX_CHARS), raw.description)
            assertEquals(longInstructions.take(SKILL_INSTRUCTIONS_MAX_CHARS), raw.instructionsMd)
            assertEquals(longParseError.take(SKILL_PARSE_ERROR_MAX_CHARS), raw.parseError)
            assertEquals(longName.take(SKILL_DISPLAY_NAME_MAX_CHARS), rawFrontmatter.name)
            assertEquals(longDescription.take(SKILL_DESCRIPTION_MAX_CHARS), rawFrontmatter.description)
            assertEquals(longBaseDir.take(SKILL_BASE_DIR_MAX_CHARS), rawFrontmatter.homepage)
            assertEquals(longSkillKey.take(SKILL_KEY_MAX_CHARS), rawFrontmatter.commandTool)
            assertEquals(expectedReasons, rawReasons)
            assertEquals(raw.skillKey, stored.skillKey)
            assertEquals(raw.instructionsMd, stored.instructionsMd)
            assertEquals(raw.parseError, stored.parseError)
            assertEquals(rawFrontmatter.name, stored.frontmatter?.name)
            assertEquals(expectedReasons, stored.eligibilityReasons)
        }

    @Test
    fun `skill reads tolerate malformed persisted json`() =
        runTest {
            database.skillRecordDao().upsert(
                skillRecordEntity(
                    id = "malformed-skill",
                    frontmatterJson = """{"name":""",
                    eligibilityReasons = "not-json",
                ),
            )

            val stored = repository.getSkill("malformed-skill")
            val observed = repository.observeSkills().first().single()

            assertNull(stored?.frontmatter)
            assertEquals(emptyList<String>(), stored?.eligibilityReasons)
            assertNull(observed.frontmatter)
            assertEquals(emptyList<String>(), observed.eligibilityReasons)
        }

    @Test
    fun `skill reads bound legacy oversized rows`() =
        runTest {
            val longName = "n".repeat(SKILL_DISPLAY_NAME_MAX_CHARS + 25)
            val longDescription = "d".repeat(SKILL_DESCRIPTION_MAX_CHARS + 25)
            val longInstructions = "i".repeat(SKILL_INSTRUCTIONS_MAX_CHARS + 25)
            val longReason = "r".repeat(SKILL_ELIGIBILITY_REASON_MAX_CHARS + 25)
            val reasons =
                listOf(longReason, "duplicate", "duplicate") +
                    (1..(SKILL_ELIGIBILITY_REASONS_MAX_COUNT + 5)).map { "legacy-reason-$it" }
            val frontmatter =
                sampleFrontmatter().copy(
                    name = longName,
                    description = longDescription,
                )
            database.skillRecordDao().upsert(
                skillRecordEntity(
                    id = "legacy-skill",
                    skillKey = "k".repeat(SKILL_KEY_MAX_CHARS + 25),
                    displayName = longName,
                    description = longDescription,
                    frontmatterJson = Json.encodeToString(SkillFrontmatter.serializer(), frontmatter),
                    instructionsMd = longInstructions,
                    eligibilityReasons = Json.encodeToString(ListSerializer(String.serializer()), reasons),
                    parseError = "p".repeat(SKILL_PARSE_ERROR_MAX_CHARS + 25),
                ),
            )

            val stored = requireNotNull(repository.getSkill("legacy-skill"))

            assertEquals(SKILL_KEY_MAX_CHARS, stored.skillKey.length)
            assertEquals(longName.take(SKILL_DISPLAY_NAME_MAX_CHARS), stored.displayName)
            assertEquals(longDescription.take(SKILL_DESCRIPTION_MAX_CHARS), stored.description)
            assertEquals(longName.take(SKILL_DISPLAY_NAME_MAX_CHARS), stored.frontmatter?.name)
            assertEquals(longDescription.take(SKILL_DESCRIPTION_MAX_CHARS), stored.frontmatter?.description)
            assertEquals(longInstructions.take(SKILL_INSTRUCTIONS_MAX_CHARS), stored.instructionsMd)
            assertEquals(SKILL_PARSE_ERROR_MAX_CHARS, stored.parseError?.length)
            assertEquals(expectedBoundedReasons(reasons), stored.eligibilityReasons)
        }

    private fun sampleFrontmatter(): SkillFrontmatter =
        SkillFrontmatter(
            name = "summary",
            description = "Summarize conversation state",
            homepage = "https://example.com/summary",
            userInvocable = true,
            disableModelInvocation = false,
            commandDispatch = SkillCommandDispatch.Model,
            commandTool = null,
            commandArgMode = "raw",
            metadata =
                buildJsonObject {
                    put("android", "supported")
                },
            unknownFields = emptyMap(),
        )

    private fun skillRecord(
        id: String,
        sourceType: SkillSourceType,
        enabled: Boolean,
        displayName: String,
        skillKey: String = displayName,
        description: String,
        frontmatter: SkillFrontmatter?,
        eligibilityStatus: SkillEligibilityStatus,
        eligibilityReasons: List<String>,
        baseDir: String =
            when (sourceType) {
                SkillSourceType.Bundled -> "asset://skills/$id"
                SkillSourceType.Local -> "/files/skills/local/$id"
                SkillSourceType.Workspace -> "/files/workspaces/session/skills/$id"
            },
        instructionsMd: String = "Do work",
        parseError: String? = null,
    ): SkillRecord =
        SkillRecord(
            id = id,
            skillKey = skillKey,
            sourceType = sourceType,
            workspaceSessionId = null,
            baseDir = baseDir,
            enabled = enabled,
            displayName = displayName,
            description = description,
            frontmatter = frontmatter,
            instructionsMd = instructionsMd,
            eligibilityStatus = eligibilityStatus,
            eligibilityReasons = eligibilityReasons,
            parseError = parseError,
            importedAt = Instant.ofEpochMilli(100L),
            updatedAt = Instant.ofEpochMilli(200L),
        )
}

private fun expectedBoundedReasons(reasons: List<String>): List<String> =
    reasons
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { it.take(SKILL_ELIGIBILITY_REASON_MAX_CHARS) }
        .distinct()
        .take(SKILL_ELIGIBILITY_REASONS_MAX_COUNT)
        .toList()

private fun skillRecordEntity(
    id: String,
    skillKey: String = "Legacy skill",
    displayName: String = "Legacy skill",
    description: String = "Legacy description",
    frontmatterJson: String? = null,
    instructionsMd: String = "Do legacy work",
    eligibilityReasons: String = "[]",
    parseError: String? = null,
): SkillRecordEntity =
    SkillRecordEntity(
        id = id,
        skillKey = skillKey,
        sourceType = "local",
        workspaceSessionId = null,
        baseDir = "/files/skills/local/$id",
        enabled = true,
        displayName = displayName,
        description = description,
        frontmatterJson = frontmatterJson,
        instructionsMd = instructionsMd,
        eligibilityStatus = "Eligible",
        eligibilityReasons = eligibilityReasons,
        parseError = parseError,
        importedAt = 100L,
        updatedAt = 200L,
    )
