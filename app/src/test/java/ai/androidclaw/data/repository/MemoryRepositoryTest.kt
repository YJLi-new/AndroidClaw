package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.MemoryItemEntity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class MemoryRepositoryTest {
    private val testClock = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        repository = MemoryRepository(database.memoryItemDao(), clock = testClock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `remember deduplicates normalized text and searches by terms`() =
        runTest {
            val first =
                repository.remember(
                    ownerUserId = "install-user",
                    text = "  User prefers compact Kotlin UI.  ",
                    sourceSessionId = "session-1",
                    sourceMessageIds = listOf("user-message"),
                )
            val duplicate =
                repository.remember(
                    ownerUserId = "install-user",
                    text = "User prefers compact Kotlin UI.",
                    sourceSessionId = "session-2",
                )

            assertEquals(first?.id, duplicate?.id)
            assertEquals(1, repository.countActive("install-user"))

            val matches = repository.search("install-user", "Kotlin UI", limit = 5)
            assertEquals(listOf(first?.id), matches.map { it.id })
            assertEquals("session-1", matches.single().sourceSessionId)
            assertEquals(listOf("user-message"), matches.single().sourceMessageIds)
            assertEquals(testClock.instant(), matches.single().createdAt)
            assertEquals(testClock.instant(), matches.single().updatedAt)
        }

    @Test
    fun `search retrieves CJK memories with compact script terms`() =
        runTest {
            val memory =
                repository.remember(
                    ownerUserId = "install-user",
                    text = "用户喜欢绿色发送按钮。",
                    sourceSessionId = "session-cn",
                )
            repository.remember(
                ownerUserId = "install-user",
                text = "用户住在上海。",
            )

            val matches = repository.search("install-user", "绿色按钮", limit = 5)

            assertEquals(listOf(memory?.id), matches.map { it.id })
            assertEquals("用户喜欢绿色发送按钮。", matches.single().text)
            assertEquals("session-cn", matches.single().sourceSessionId)
        }

    @Test
    fun `list for source session returns active owner scoped recent memories`() =
        runTest {
            val first =
                requireNotNull(
                    repository.remember(
                        ownerUserId = "install-user",
                        text = "First session memory.",
                        sourceSessionId = "session-1",
                    ),
                )
            repository.remember(
                ownerUserId = "install-user",
                text = "Different source session memory.",
                sourceSessionId = "session-2",
            )
            val second =
                requireNotNull(
                    repository.remember(
                        ownerUserId = "install-user",
                        text = "Second session memory.",
                        sourceSessionId = "session-1",
                    ),
                )
            repository.remember(
                ownerUserId = "other-user",
                text = "Other owner same source session memory.",
                sourceSessionId = "session-1",
            )

            val matches = repository.listForSourceSession("install-user", " session-1 ", limit = 5)
            val limitedMatches = repository.listForSourceSession("install-user", "session-1", limit = 1)
            assertTrue(repository.delete("install-user", first.id))
            val activeMatches = repository.listForSourceSession("install-user", "session-1", limit = 5)

            assertEquals(listOf(second.id, first.id), matches.map { it.id })
            assertEquals(listOf(second.id), limitedMatches.map { it.id })
            assertEquals(listOf(second.id), activeMatches.map { it.id })
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.listForSourceSession("", "session-1", 5))
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.listForSourceSession("install-user", " ", 5))
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.listForSourceSession("install-user", "session-1", 0))
        }

    @Test
    fun `list for source type returns active owner scoped recent memories`() =
        runTest {
            val deletedAutomatic =
                requireNotNull(
                    repository.remember(
                        ownerUserId = "install-user",
                        text = "Deleted automatic memory.",
                        sourceType = MemoryRepository.SOURCE_TYPE_AUTOMATIC,
                    ),
                )
            repository.remember(
                ownerUserId = "install-user",
                text = "Manual memory.",
                sourceType = MemoryRepository.SOURCE_TYPE_MANUAL,
            )
            val automatic =
                requireNotNull(
                    repository.remember(
                        ownerUserId = "install-user",
                        text = "Active automatic memory.",
                        sourceType = MemoryRepository.SOURCE_TYPE_AUTOMATIC,
                    ),
                )
            repository.remember(
                ownerUserId = "other-user",
                text = "Other owner automatic memory.",
                sourceType = MemoryRepository.SOURCE_TYPE_AUTOMATIC,
            )

            val matches = repository.listForSourceType("install-user", " automatic ", limit = 5)
            val limitedMatches = repository.listForSourceType("install-user", "automatic", limit = 1)
            assertTrue(repository.delete("install-user", deletedAutomatic.id))
            val activeMatches = repository.listForSourceType("install-user", "automatic", limit = 5)

            assertEquals(listOf(automatic.id, deletedAutomatic.id), matches.map { it.id })
            assertEquals(listOf(automatic.id), limitedMatches.map { it.id })
            assertEquals(listOf(automatic.id), activeMatches.map { it.id })
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.listForSourceType("", "automatic", 5))
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.listForSourceType("install-user", " ", 5))
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.listForSourceType("install-user", "automatic", 0))
        }

    @Test
    fun `delete and clear hide active memories`() =
        runTest {
            val first = repository.remember("install-user", "User likes green buttons.")
            repository.remember("install-user", "User uses Android Studio.")
            val firstId = requireNotNull(first).id

            assertEquals(first, repository.get("install-user", firstId))
            assertTrue(repository.delete("install-user", firstId))
            assertEquals(null, repository.get("install-user", firstId))
            assertFalse(repository.search("install-user", "green", limit = 5).any { it.id == firstId })
            assertEquals(1, repository.countActive("install-user"))

            assertEquals(1, repository.clear("install-user"))
            assertEquals(0, repository.countActive("install-user"))
        }

    @Test
    fun `stats aggregates active deleted and source type memory state`() =
        runTest {
            val manual =
                requireNotNull(
                    repository.remember(
                        ownerUserId = "install-user",
                        text = "User likes green buttons.",
                        sourceSessionId = "session-1",
                        sourceType = MemoryRepository.SOURCE_TYPE_MANUAL,
                    ),
                )
            repository.remember(
                ownerUserId = "install-user",
                text = "User prefers compact layouts.",
                sourceType = MemoryRepository.SOURCE_TYPE_AUTOMATIC,
            )
            repository.remember(
                ownerUserId = "other-user",
                text = "Other user's memory is isolated.",
                sourceType = MemoryRepository.SOURCE_TYPE_AUTOMATIC,
            )
            assertTrue(repository.delete("install-user", manual.id))

            val stats = repository.stats("install-user")
            val sourceStats = stats.sourceTypeStats.associate { item -> item.sourceType to item.memoryCount }
            val blankStats = repository.stats("")

            assertEquals(2L, stats.totalMemoryCount)
            assertEquals(1L, stats.activeMemoryCount)
            assertEquals(1L, stats.deletedMemoryCount)
            assertEquals(0L, stats.activeWithSourceSessionCount)
            assertEquals(testClock.instant(), stats.oldestActiveCreatedAt)
            assertEquals(testClock.instant(), stats.newestActiveUpdatedAt)
            assertEquals(mapOf(MemoryRepository.SOURCE_TYPE_AUTOMATIC to 1L), sourceStats)
            assertEquals(0L, blankStats.totalMemoryCount)
            assertEquals(emptyList<MemoryRepository.SourceTypeStats>(), blankStats.sourceTypeStats)
        }

    @Test
    fun `update replaces active memory text and timestamp`() =
        runTest {
            val first =
                requireNotNull(
                    repository.remember(
                        ownerUserId = "install-user",
                        text = "User likes green buttons.",
                        sourceSessionId = "session-1",
                        sourceMessageIds = listOf("message-1"),
                    ),
                )
            val laterClock = Clock.fixed(Instant.parse("2026-06-15T13:00:00Z"), ZoneOffset.UTC)
            val laterRepository = MemoryRepository(database.memoryItemDao(), clock = laterClock)

            val updated =
                laterRepository.update(
                    ownerUserId = "install-user",
                    id = first.id,
                    text = "  User likes blue buttons.  ",
                )

            assertEquals(first.id, updated?.id)
            assertEquals("User likes blue buttons.", updated?.text)
            assertEquals(testClock.instant(), updated?.createdAt)
            assertEquals(laterClock.instant(), updated?.updatedAt)
            assertEquals("session-1", updated?.sourceSessionId)
            assertEquals(listOf("message-1"), updated?.sourceMessageIds)
            assertEquals(listOf(first.id), laterRepository.search("install-user", "blue", limit = 5).map { it.id })
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), laterRepository.search("install-user", "green", limit = 5))
            assertEquals(null, laterRepository.update("install-user", "missing-memory", "New text."))
            assertEquals(null, laterRepository.update("install-user", first.id, "   "))

            assertTrue(laterRepository.delete("install-user", first.id))
            assertEquals(null, laterRepository.update("install-user", first.id, "Deleted text."))
        }

    @Test
    fun `remember bounds text and source message ids before persistence`() =
        runTest {
            val longText = "x".repeat(MemoryRepository.MAX_MEMORY_TEXT_CHARS + 25)
            val longId = "m".repeat(MemoryRepository.MAX_SOURCE_MESSAGE_ID_CHARS + 25)
            val sourceMessageIds =
                listOf(longId, " first ", "first", " ") +
                    (1..(MemoryRepository.MAX_SOURCE_MESSAGE_IDS + 5)).map { "message-$it" }
            val expectedSourceIds = expectedBoundedSourceIds(sourceMessageIds)

            val memory =
                repository.remember(
                    ownerUserId = "install-user",
                    text = longText,
                    sourceMessageIds = sourceMessageIds,
                )
            val raw = database.memoryItemDao().getActiveByOwner("install-user", limit = 1).single()
            val rawSourceIds = Json.decodeFromString<List<String>>(raw.sourceMessageIdsJson)

            assertEquals(longText.take(MemoryRepository.MAX_MEMORY_TEXT_CHARS), memory?.text)
            assertEquals(longText.take(MemoryRepository.MAX_MEMORY_TEXT_CHARS), raw.text)
            assertEquals(expectedSourceIds, memory?.sourceMessageIds)
            assertEquals(expectedSourceIds, rawSourceIds)
        }

    @Test
    fun `search list and observed count handle blank owners and non-positive limits`() =
        runTest {
            repository.remember("install-user", "User likes small Android apps.")

            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.search("install-user", "Android", 0))
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.search("install-user", "Android", -5))
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.search("", "Android", 5))
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.listRecent("install-user", 0))
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.listRecent("install-user", -5))
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), repository.listRecent("", 5))
            assertEquals(0, repository.countActive(""))
            assertEquals(0, repository.observeActiveCount("").first())
        }

    @Test
    fun `memory reads bound legacy oversized rows`() =
        runTest {
            val longText = "a".repeat(MemoryRepository.MAX_MEMORY_TEXT_CHARS) + " needle-after-bound"
            val longId = "m".repeat(MemoryRepository.MAX_SOURCE_MESSAGE_ID_CHARS + 25)
            val legacySourceMessageIds =
                listOf(longId, " first ", "first", " ") +
                    (1..(MemoryRepository.MAX_SOURCE_MESSAGE_IDS + 5)).map { "legacy-$it" }
            database.memoryItemDao().insert(
                memoryItemEntity(
                    id = "legacy-memory",
                    text = longText,
                    sourceMessageIdsJson = Json.encodeToString(legacySourceMessageIds),
                ),
            )

            val listed = repository.listRecent("install-user", limit = 5).single()
            val matches = repository.search("install-user", "needle-after-bound", limit = 5)

            assertEquals(longText.take(MemoryRepository.MAX_MEMORY_TEXT_CHARS), listed.text)
            assertEquals(expectedBoundedSourceIds(legacySourceMessageIds), listed.sourceMessageIds)
            assertEquals(emptyList<ai.androidclaw.data.model.MemoryItem>(), matches)
        }
}

private fun expectedBoundedSourceIds(sourceMessageIds: List<String>): List<String> =
    sourceMessageIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { it.take(MemoryRepository.MAX_SOURCE_MESSAGE_ID_CHARS) }
        .distinct()
        .take(MemoryRepository.MAX_SOURCE_MESSAGE_IDS)
        .toList()

private fun memoryItemEntity(
    id: String,
    text: String,
    sourceMessageIdsJson: String,
): MemoryItemEntity =
    MemoryItemEntity(
        id = id,
        ownerUserId = "install-user",
        text = text,
        sourceSessionId = "session-1",
        sourceMessageIdsJson = sourceMessageIdsJson,
        sourceType = MemoryRepository.SOURCE_TYPE_MANUAL,
        createdAt = 1L,
        updatedAt = 1L,
        deletedAt = null,
    )
