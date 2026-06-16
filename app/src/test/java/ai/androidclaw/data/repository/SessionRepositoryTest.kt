package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.SessionEntity
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
    fun `update title archive and unarchive session persist state`() =
        runTest {
            val created = repository.createSession(title = "Draft")

            repository.updateTitle(created.id, "Renamed")
            repository.archiveSession(created.id)

            val stored = repository.getSession(created.id)
            assertNotNull(stored)
            assertEquals("Renamed", stored?.title)
            assertTrue(stored?.archived == true)
            assertTrue(repository.observeSessions().first().isEmpty())

            repository.unarchiveSession(created.id)

            val restored = repository.getSession(created.id)
            assertTrue(restored?.archived == false)
            assertEquals(listOf(created.id), repository.observeSessions().first().map { it.id })
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
    fun `blank session search queries return empty results`() =
        runTest {
            repository.createSession("Alpha plan")

            assertEquals(emptyList<SessionRepository.SearchResult>(), repository.searchSessions("", limit = 10))
            assertEquals(emptyList<SessionRepository.SearchResult>(), repository.searchSessions("   ", limit = 10))
        }

    @Test
    fun `session search treats sql wildcard characters as literal text`() =
        runTest {
            val literal = repository.createSession("""Path C:\tmp is 100%_ready""")
            val normal = repository.createSession("Alpha plan")

            assertEquals(listOf(literal.id), repository.searchSessions("%", limit = 10).map { it.sessionId })
            assertEquals(listOf(literal.id), repository.searchSessions("_", limit = 10).map { it.sessionId })
            assertEquals(listOf(literal.id), repository.searchSessions("""\""", limit = 10).map { it.sessionId })
            assertEquals(listOf(literal.id), repository.searchSessions("%_ready", limit = 10).map { it.sessionId })
            assertEquals(listOf(normal.id), repository.searchSessions("plan", limit = 10).map { it.sessionId })
        }

    @Test
    fun `oversized session search query returns empty results`() =
        runTest {
            repository.createSession("Alpha plan")

            assertEquals(
                emptyList<SessionRepository.SearchResult>(),
                repository.searchSessions("Alpha".repeat(SQLITE_LIKE_SEARCH_QUERY_MAX_CHARS), limit = 10),
            )
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

    @Test
    fun `session titles are bounded before persistence and on read`() =
        runTest {
            val longTitle = "t".repeat(SESSION_TITLE_MAX_CHARS + 20)

            val created = repository.createSession(title = longTitle)
            repository.updateTitle(created.id, longTitle + " updated")

            val raw = database.sessionDao().getById(created.id)
            val stored = repository.getSession(created.id)

            assertEquals(SESSION_TITLE_MAX_CHARS, raw?.title?.length)
            assertEquals(SESSION_TITLE_MAX_CHARS, stored?.title?.length)
        }

    @Test
    fun `session summaries are bounded before persistence and on read`() =
        runTest {
            val created = repository.createSession(title = "Summarize me")
            val longSummary = "s".repeat(SESSION_SUMMARY_MAX_CHARS + 20)

            repository.updateSummaryAndCompactionBoundary(
                id = created.id,
                summaryText = longSummary,
                compactedUntilMessageId = "message-1",
            )

            val rawWithBoundary = database.sessionDao().getById(created.id)
            assertEquals(SESSION_SUMMARY_MAX_CHARS, rawWithBoundary?.summaryText?.length)

            repository.updateSummary(created.id, longSummary + " updated")
            val raw = database.sessionDao().getById(created.id)
            val stored = repository.getSession(created.id)

            assertEquals(SESSION_SUMMARY_MAX_CHARS, raw?.summaryText?.length)
            assertEquals(SESSION_SUMMARY_MAX_CHARS, stored?.summaryText?.length)
            assertEquals("message-1", stored?.compactedUntilMessageId)
        }

    @Test
    fun `compaction boundary ids are bounded before persistence and on read`() =
        runTest {
            val created = repository.createSession(title = "Compact me")
            val longBoundaryId = "boundary-" + "b".repeat(SESSION_COMPACTION_BOUNDARY_ID_MAX_CHARS + 20)
            val expectedBoundaryId = longBoundaryId.take(SESSION_COMPACTION_BOUNDARY_ID_MAX_CHARS)

            repository.updateSummaryAndCompactionBoundary(
                id = created.id,
                summaryText = "Compact summary.",
                compactedUntilMessageId = longBoundaryId,
            )

            val raw = database.sessionDao().getById(created.id)
            val stored = repository.getSession(created.id)
            val withBoundary = repository.getSessionsWithCompactionBoundary().single()

            assertEquals(expectedBoundaryId, raw?.compactedUntilMessageId)
            assertEquals(expectedBoundaryId, stored?.compactedUntilMessageId)
            assertEquals(expectedBoundaryId, withBoundary.compactedUntilMessageId)
        }

    @Test
    fun `blank compaction boundary ids are stored as null and ignored by boundary query`() =
        runTest {
            val created = repository.createSession(title = "Blank boundary")

            repository.updateSummaryAndCompactionBoundary(
                id = created.id,
                summaryText = "Compact summary.",
                compactedUntilMessageId = "   ",
            )

            assertEquals(null, database.sessionDao().getById(created.id)?.compactedUntilMessageId)
            assertEquals(null, repository.getSession(created.id)?.compactedUntilMessageId)
            assertEquals(emptyList<ai.androidclaw.data.model.Session>(), repository.getSessionsWithCompactionBoundary())
        }

    @Test
    fun `legacy oversized session rows are bounded on read and search`() =
        runTest {
            val longBoundaryId = "legacy-boundary-" + "b".repeat(SESSION_COMPACTION_BOUNDARY_ID_MAX_CHARS + 20)
            database.sessionDao().insert(
                SessionEntity(
                    id = "legacy-session",
                    title = "Legacy ".repeat(SESSION_TITLE_MAX_CHARS),
                    isMain = false,
                    createdAt = 1L,
                    updatedAt = 1L,
                    archivedAt = null,
                    summaryText = "summary ".repeat(SESSION_SUMMARY_MAX_CHARS),
                    compactedUntilMessageId = longBoundaryId,
                ),
            )

            val stored = repository.getSession("legacy-session")
            val searchResult = repository.searchSessions("Legacy", limit = 1).single()
            val withBoundary = repository.getSessionsWithCompactionBoundary().single()

            assertEquals(SESSION_TITLE_MAX_CHARS, stored?.title?.length)
            assertEquals(SESSION_SUMMARY_MAX_CHARS, stored?.summaryText?.length)
            assertEquals(longBoundaryId.take(SESSION_COMPACTION_BOUNDARY_ID_MAX_CHARS), stored?.compactedUntilMessageId)
            assertEquals(longBoundaryId.take(SESSION_COMPACTION_BOUNDARY_ID_MAX_CHARS), withBoundary.compactedUntilMessageId)
            assertEquals(SESSION_TITLE_MAX_CHARS, searchResult.sessionTitle.length)
        }

    @Test
    fun `legacy blank compaction boundary rows are ignored by boundary query`() =
        runTest {
            database.sessionDao().insert(
                SessionEntity(
                    id = "blank-legacy-boundary",
                    title = "Blank legacy boundary",
                    isMain = false,
                    createdAt = 1L,
                    updatedAt = 1L,
                    archivedAt = null,
                    summaryText = "summary",
                    compactedUntilMessageId = "   ",
                ),
            )

            assertEquals(null, repository.getSession("blank-legacy-boundary")?.compactedUntilMessageId)
            assertEquals(emptyList<ai.androidclaw.data.model.Session>(), repository.getSessionsWithCompactionBoundary())
        }
}
