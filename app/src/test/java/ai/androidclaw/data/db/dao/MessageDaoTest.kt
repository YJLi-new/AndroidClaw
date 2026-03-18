package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.MessageEntity
import ai.androidclaw.data.db.entity.SessionEntity
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
class MessageDaoTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        sessionDao = database.sessionDao()
        dao = database.messageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `query session messages in ascending order and recent limit in descending order`() =
        runTest {
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
            dao.insertAll(
                listOf(
                    MessageEntity("m1", "main", "user", "hello", 10L, null, null, null),
                    MessageEntity("m2", "main", "assistant", "world", 20L, null, null, null),
                    MessageEntity("m3", "main", "tool_result", "done", 30L, "{\"latency\":1}", "call-1", null),
                ),
            )

            val messages = dao.getBySessionId("main").first()
            val allMessages = dao.getAllBySessionId("main")
            assertEquals(listOf("m1", "m2", "m3"), messages.map { it.id })
            assertEquals(listOf("m1", "m2", "m3"), allMessages.map { it.id })

            val recent = dao.getRecentBySessionId("main", limit = 2)
            assertEquals(listOf("m3", "m2"), recent.map { it.id })
            assertEquals(3, dao.countBySessionId("main"))

            dao.deleteBySessionId("main")
            assertEquals(0, dao.countBySessionId("main"))
        }
}
