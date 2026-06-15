package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    fun `get or create main session emits through observeSessions and reuses existing row`() =
        runTest {
            val emitted =
                async {
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
    fun `get or create main session serializes concurrent callers`() =
        runTest {
            val sessions =
                (1..20)
                    .map { async { repository.getOrCreateMainSession() } }
                    .awaitAll()

            assertEquals(1, sessions.map { it.id }.toSet().size)
            assertEquals(1, repository.observeSessions().first().count { it.isMain })
        }

    @Test
    fun `update title and archive session persist state`() =
        runTest {
            val created = repository.createSession(title = "Draft")

            repository.updateTitle(created.id, "Renamed")
            repository.archiveSession(created.id)

            val stored = repository.getSession(created.id)
            assertNotNull(stored)
            assertEquals("Renamed", stored?.title)
            assertTrue(stored?.archived == true)
            assertTrue(repository.observeSessions().first().isEmpty())
        }

    @Test
    fun `search sessions matches active titles only`() =
        runTest {
            val alpha = repository.createSession("Alpha plan")
            val beta = repository.createSession("Beta notes")
            repository.archiveSession(beta.id)

            val results = repository.searchSessions("plan", limit = 10)

            assertEquals(listOf(alpha.id), results.map { it.sessionId })
            assertEquals("Alpha plan", results.single().sessionTitle)
        }

    @Test
    fun `non-positive session search limits return empty results`() =
        runTest {
            repository.createSession("Alpha plan")

            assertEquals(emptyList<SessionRepository.SearchResult>(), repository.searchSessions("Alpha", limit = 0))
            assertEquals(emptyList<SessionRepository.SearchResult>(), repository.searchSessions("Alpha", limit = -1))
        }

    @Test
    fun `session search limits are capped at repository boundary`() =
        runTest {
            repeat(SESSION_SEARCH_MAX_LIMIT + 2) { index ->
                repository.createSession("Alpha plan $index")
            }

            val results = repository.searchSessions("Alpha", limit = Int.MAX_VALUE)

            assertEquals(SESSION_SEARCH_MAX_LIMIT, results.size)
        }

    @Test
    fun `summary updates preserve and set compaction boundary`() =
        runTest {
            val created = repository.createSession(title = "Compact me")

            repository.updateSummaryAndCompactionBoundary(
                id = created.id,
                summaryText = "Initial compact summary.",
                compactedUntilMessageId = "message-1",
            )
            repository.updateSummary(created.id, "Background refreshed summary.")

            val stored = repository.getSession(created.id)
            assertEquals("Background refreshed summary.", stored?.summaryText)
            assertEquals("message-1", stored?.compactedUntilMessageId)
        }
}
