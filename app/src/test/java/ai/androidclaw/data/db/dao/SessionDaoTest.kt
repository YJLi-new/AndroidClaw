package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.SessionEntity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import junit.framework.TestCase.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        dao = database.sessionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert and query sessions with active and archived filtering`() = runTest {
        val main = SessionEntity(
            id = "main",
            title = "Main session",
            isMain = true,
            createdAt = 10L,
            updatedAt = 50L,
            archivedAt = null,
            summaryText = null,
        )
        val archived = SessionEntity(
            id = "archived",
            title = "Old session",
            isMain = false,
            createdAt = 20L,
            updatedAt = 20L,
            archivedAt = 30L,
            summaryText = "done",
        )
        val active = SessionEntity(
            id = "active",
            title = "Fresh session",
            isMain = false,
            createdAt = 30L,
            updatedAt = 100L,
            archivedAt = null,
            summaryText = null,
        )

        dao.insert(main)
        dao.insert(archived)
        dao.insert(active)

        assertEquals("main", dao.getMainSession()?.id)
        assertNotNull(dao.getById("active"))
        assertNull(dao.getById("missing"))

        val activeSessions = dao.getAllSessions().first()
        assertEquals(listOf("active", "main"), activeSessions.map { it.id })

        val archivedSessions = dao.getArchivedSessions().first()
        assertEquals(listOf("archived"), archivedSessions.map { it.id })
    }
}

