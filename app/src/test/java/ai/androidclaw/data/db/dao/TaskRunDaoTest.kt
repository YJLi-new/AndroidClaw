package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.db.entity.TaskEntity
import ai.androidclaw.data.db.entity.TaskRunEntity
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
class TaskRunDaoTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var taskDao: TaskDao
    private lateinit var dao: TaskRunDao

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        sessionDao = database.sessionDao()
        taskDao = database.taskDao()
        dao = database.taskRunDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `query latest runs and trim older history`() = runTest {
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
        taskDao.insert(
            TaskEntity(
                id = "task-1",
                name = "Task",
                prompt = "Prompt",
                scheduleKind = "once",
                scheduleSpec = "{\"at\":100}",
                executionMode = "MAIN_SESSION",
                targetSessionId = "main",
                enabled = true,
                precise = false,
                nextRunAt = 100L,
                lastRunAt = null,
                failureCount = 0,
                maxRetries = 3,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        dao.insert(run("run-1", scheduledAt = 10L))
        dao.insert(run("run-2", scheduledAt = 20L))
        dao.insert(run("run-3", scheduledAt = 30L))

        val runs = dao.getByTaskId("task-1").first()
        assertEquals(listOf("run-3", "run-2", "run-1"), runs.map { it.id })
        assertEquals("run-3", dao.getLatestByTaskId("task-1")?.id)

        dao.deleteOlderThan(21L)
        assertEquals(listOf("run-3"), dao.getByTaskId("task-1").first().map { it.id })
    }

    private fun run(id: String, scheduledAt: Long): TaskRunEntity {
        return TaskRunEntity(
            id = id,
            taskId = "task-1",
            status = "SUCCESS",
            scheduledAt = scheduledAt,
            startedAt = scheduledAt,
            finishedAt = scheduledAt + 1,
            errorCode = null,
            errorMessage = null,
            resultSummary = "ok",
            outputMessageId = null,
        )
    }
}

