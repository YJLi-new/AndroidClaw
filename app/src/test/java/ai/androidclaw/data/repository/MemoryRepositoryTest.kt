package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryRepositoryTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        repository = MemoryRepository(database.memoryItemDao())
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
    fun `delete and clear hide active memories`() =
        runTest {
            val first = repository.remember("install-user", "User likes green buttons.")
            repository.remember("install-user", "User uses Android Studio.")

            assertTrue(repository.delete("install-user", requireNotNull(first).id))
            assertFalse(repository.search("install-user", "green", limit = 5).any { it.id == first.id })
            assertEquals(1, repository.countActive("install-user"))

            assertEquals(1, repository.clear("install-user"))
            assertEquals(0, repository.countActive("install-user"))
        }
}
