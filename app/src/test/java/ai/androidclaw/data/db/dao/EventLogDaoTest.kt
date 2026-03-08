package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.EventLogEntity
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
class EventLogDaoTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var dao: EventLogDao

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        dao = database.eventLogDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `read recent events in descending order and trim old rows`() = runTest {
        dao.insert(EventLogEntity("e1", 10L, "system", "info", "boot", null))
        dao.insert(EventLogEntity("e2", 20L, "skill", "warn", "skill missing", "{\"id\":\"task\"}"))
        dao.insert(EventLogEntity("e3", 30L, "scheduler", "error", "run failed", null))

        val recent = dao.getRecent(limit = 2).first()
        assertEquals(listOf("e3", "e2"), recent.map { it.id })
        assertEquals(3, dao.count())

        dao.deleteOlderThan(21L)
        assertEquals(listOf("e3"), dao.getRecent(limit = 10).first().map { it.id })
        assertEquals(1, dao.count())
    }
}
