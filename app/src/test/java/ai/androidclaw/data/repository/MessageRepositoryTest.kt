package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.model.MessageRole
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

        val recent = repository.getRecentMessages("main", limit = 2)
        assertEquals(listOf(MessageRole.Assistant, MessageRole.User), recent.map { it.role })
        assertTrue(recent.first().providerMeta?.contains("fake") == true)
    }
}
