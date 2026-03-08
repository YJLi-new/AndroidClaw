package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.db.entity.TaskEntity
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
class TaskDaoTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var dao: TaskDao

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        sessionDao = database.sessionDao()
        dao = database.taskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `return due enabled tasks and order null next runs last`() = runTest {
        sessionDao.insert(
            SessionEntity(
                id = "main",
                title = "Main session",
                isMain = true,
                createdAt = 1L,
                updatedAt = 1L,
                archivedAt = null,
                summaryText = null,
            ),
        )
        dao.insert(
            task(
                id = "due",
                nextRunAt = 100L,
                enabled = true,
            ),
        )
        dao.insert(
            task(
                id = "future",
                nextRunAt = 500L,
                enabled = true,
            ),
        )
        dao.insert(
            task(
                id = "disabled",
                nextRunAt = 50L,
                enabled = false,
            ),
        )
        dao.insert(
            task(
                id = "unscheduled",
                nextRunAt = null,
                enabled = true,
            ),
        )

        val due = dao.getEnabledTasksDueBefore(instant = 150L)
        assertEquals(listOf("due"), due.map { it.id })

        val ordered = dao.getAllTasks().first()
        assertEquals(listOf("disabled", "due", "future", "unscheduled"), ordered.map { it.id })
    }

    private fun task(id: String, nextRunAt: Long?, enabled: Boolean): TaskEntity {
        return TaskEntity(
            id = id,
            name = "Task $id",
            prompt = "Prompt $id",
            scheduleKind = "once",
            scheduleSpec = "{\"at\":100}",
            executionMode = "MAIN_SESSION",
            targetSessionId = "main",
            enabled = enabled,
            precise = false,
            nextRunAt = nextRunAt,
            lastRunAt = null,
            failureCount = 0,
            maxRetries = 3,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }
}
