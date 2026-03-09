package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.SkillRecordEntity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkillRecordDaoTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var dao: SkillRecordDao

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        dao = database.skillRecordDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upsert records and filter enabled skills`() = runTest {
        dao.upsert(
            SkillRecordEntity(
                id = "bundled-summary",
                skillKey = "summary",
                sourceType = "bundled",
                workspaceSessionId = null,
                baseDir = "asset://skills/summary",
                enabled = false,
                displayName = "Summary",
                description = "Initial",
                frontmatterJson = null,
                instructionsMd = "Do work",
                eligibilityStatus = "Eligible",
                eligibilityReasons = "[]",
                parseError = null,
                importedAt = null,
                updatedAt = 1L,
            ),
        )
        dao.upsert(
            SkillRecordEntity(
                id = "bundled-summary",
                skillKey = "summary",
                sourceType = "bundled",
                workspaceSessionId = null,
                baseDir = "asset://skills/summary",
                enabled = true,
                displayName = "Summary Updated",
                description = "Updated",
                frontmatterJson = "{\"name\":\"summary\"}",
                instructionsMd = "Do work",
                eligibilityStatus = "Eligible",
                eligibilityReasons = "[]",
                parseError = null,
                importedAt = null,
                updatedAt = 2L,
            ),
        )
        dao.upsert(
            SkillRecordEntity(
                id = "local-task",
                skillKey = "task-helper",
                sourceType = "local",
                workspaceSessionId = null,
                baseDir = "/files/skills/local/task-helper",
                enabled = false,
                displayName = "Task helper",
                description = "Task",
                frontmatterJson = null,
                instructionsMd = "Do task work",
                eligibilityStatus = "MissingTool",
                eligibilityReasons = "[\"Missing tool: tasks.list\"]",
                parseError = null,
                importedAt = 3L,
                updatedAt = 3L,
            ),
        )

        assertEquals("Summary Updated", dao.getById("bundled-summary")?.displayName)
        assertEquals(listOf("bundled-summary"), dao.getEnabled().map { it.id })
        assertEquals(listOf("bundled-summary", "local-task"), dao.getAll().first().map { it.id })
    }
}
