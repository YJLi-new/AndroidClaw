package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.MessageEntity
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.model.MessageRole
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MessageRepositoryTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: MessageRepository

    @Before
    fun setUp() = runTest {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        repository = MessageRepository(database.messageDao())
        database.sessionDao().insert(
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `add message emits flow and recent queries return typed roles`() = runTest {
        val emitted = async {
            repository.observeMessages("main").first { messages ->
                messages.any { it.content == "hello" }
            }
        }

        val user = repository.addMessage(
            sessionId = "main",
            role = MessageRole.User,
            content = "hello",
        )

        repository.addMessage(
            sessionId = "main",
            role = MessageRole.Assistant,
            content = "world",
            providerMeta = "{\"provider\":\"fake\"}",
        )

        assertEquals(MessageRole.User, user.role)
        assertTrue(emitted.await().any { it.content == "hello" })

        val allMessages = repository.getMessages("main")
        val recent = repository.getRecentMessages("main", limit = 2)
        assertEquals(listOf(MessageRole.User, MessageRole.Assistant), allMessages.map { it.role })
        assertEquals(listOf(MessageRole.Assistant, MessageRole.User), recent.map { it.role })
        assertTrue(recent.first().providerMeta?.contains("fake") == true)
    }

    @Test
    fun `same timestamp messages keep insertion order`() = runTest {
        val sameTimestamp = Instant.parse("2026-03-12T07:00:00Z").toEpochMilli()
        database.messageDao().insertAll(
            listOf(
                MessageEntity(
                    id = "msg-a",
                    sessionId = "main",
                    role = "user",
                    content = "a1",
                    createdAt = sameTimestamp,
                    providerMeta = null,
                    toolCallId = null,
                    taskRunId = null,
                ),
                MessageEntity(
                    id = "msg-b",
                    sessionId = "main",
                    role = "assistant",
                    content = "a2",
                    createdAt = sameTimestamp,
                    providerMeta = null,
                    toolCallId = null,
                    taskRunId = null,
                ),
                MessageEntity(
                    id = "msg-c",
                    sessionId = "main",
                    role = "user",
                    content = "b1",
                    createdAt = sameTimestamp,
                    providerMeta = null,
                    toolCallId = null,
                    taskRunId = null,
                ),
                MessageEntity(
                    id = "msg-d",
                    sessionId = "main",
                    role = "assistant",
                    content = "b2",
                    createdAt = sameTimestamp,
                    providerMeta = null,
                    toolCallId = null,
                    taskRunId = null,
                ),
            ),
        )

        val observed = repository.observeMessages("main").first()
        val recent = repository.getRecentMessages("main", limit = 10).asReversed()

        assertEquals(listOf("a1", "a2", "b1", "b2"), observed.map { it.content })
        assertEquals(listOf("a1", "a2", "b1", "b2"), recent.map { it.content })
    }

    @Test
    fun `search messages returns active session matches with session titles`() = runTest {
        database.sessionDao().insert(
            SessionEntity(
                id = "archived",
                title = "Archived session",
                isMain = false,
                createdAt = 2L,
                updatedAt = 2L,
                archivedAt = 3L,
                summaryText = null,
            ),
        )
        repository.addMessage(
            sessionId = "main",
            role = MessageRole.Assistant,
            content = "Alpha status is green",
        )
        repository.addMessage(
            sessionId = "archived",
            role = MessageRole.Assistant,
            content = "Alpha from archived session",
        )

        val results = repository.searchMessages("Alpha", limit = 10)

        assertEquals(1, results.size)
        assertEquals("main", results.single().sessionId)
        assertEquals("Main session", results.single().sessionTitle)
        assertEquals("Alpha status is green", results.single().content)
    }
}
