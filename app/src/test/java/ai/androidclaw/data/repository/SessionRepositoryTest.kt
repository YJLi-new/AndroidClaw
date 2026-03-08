package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        repository = SessionRepository(database.sessionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `get or create main session emits through observeSessions and reuses existing row`() = runTest {
        val emitted = async {
            repository.observeSessions().first { sessions -> sessions.any { it.isMain } }
        }

        val first = repository.getOrCreateMainSession()
        val second = repository.getOrCreateMainSession()

        assertEquals(first.id, second.id)
        assertTrue(first.isMain)
        assertFalse(first.archived)
        assertEquals(listOf(first.id), emitted.await().map { it.id })
    }

    @Test
    fun `update title and archive session persist state`() = runTest {
        val created = repository.createSession(title = "Draft")

        repository.updateTitle(created.id, "Renamed")
        repository.archiveSession(created.id)

        val stored = repository.getSession(created.id)
        assertNotNull(stored)
        assertEquals("Renamed", stored?.title)
        assertTrue(stored?.archived == true)
        assertTrue(repository.observeSessions().first().isEmpty())
    }
}
