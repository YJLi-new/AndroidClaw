package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.MessageEntity
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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MessageRepositoryTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: MessageRepository

    @Before
    fun setUp() =
        runTest {
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
    fun `add message emits flow and recent queries return typed roles`() =
        runTest {
            val emitted =
                async {
                    repository.observeMessages("main").first { messages ->
                        messages.any { it.content == "hello" }
                    }
                }

            val user =
                repository.addMessage(
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
    fun `same timestamp messages keep insertion order`() =
        runTest {
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
    fun `search messages returns active session matches with session titles`() =
        runTest {
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

    @Test
    fun `non-positive message query limits return empty results`() =
        runTest {
            repository.addMessage(
                sessionId = "main",
                role = MessageRole.User,
                content = "Alpha status is green",
            )

            assertEquals(emptyList<ai.androidclaw.data.model.ChatMessage>(), repository.getRecentMessages("main", limit = 0))
            assertEquals(emptyList<ai.androidclaw.data.model.ChatMessage>(), repository.getRecentMessages("main", limit = -1))
            assertEquals(emptyList<MessageRepository.SearchResult>(), repository.searchMessages("Alpha", limit = 0))
            assertEquals(emptyList<MessageRepository.SearchResult>(), repository.searchMessages("Alpha", limit = -1))
        }

    @Test
    fun `message query limits are capped at repository boundary`() =
        runTest {
            repeat(MESSAGE_QUERY_MAX_LIMIT + 2) { index ->
                repository.addMessage(
                    sessionId = "main",
                    role = MessageRole.User,
                    content = "bounded-$index",
                )
            }

            val recent = repository.getRecentMessages("main", limit = Int.MAX_VALUE)
            val search = repository.searchMessages("bounded", limit = Int.MAX_VALUE)

            assertEquals(MESSAGE_QUERY_MAX_LIMIT, recent.size)
            assertEquals(MESSAGE_QUERY_MAX_LIMIT, search.size)
        }

    @Test
    fun `getMessagesByIds batches large id collections without dropping matches`() =
        runTest {
            val messages =
                (0 until MESSAGE_ID_BATCH_SIZE + 2).map { index ->
                    repository.addMessage(
                        sessionId = "main",
                        role = MessageRole.Assistant,
                        content = "message-$index",
                    )
                }

            val byId = repository.getMessagesByIds(messages.map { it.id } + messages.first().id)

            assertEquals(messages.size, byId.size)
            assertEquals(messages.map { it.id }.toSet(), byId.keys)
        }

    @Test
    fun `add message bounds content metadata and reference ids before persistence`() =
        runTest {
            val longContent = "c".repeat(MESSAGE_CONTENT_MAX_CHARS + 25)
            val longProviderMeta = "m".repeat(MESSAGE_PROVIDER_META_MAX_CHARS + 25)
            val longToolCallId = "tool-".repeat(MESSAGE_REFERENCE_ID_MAX_CHARS)
            val longTaskRunId = "run-".repeat(MESSAGE_REFERENCE_ID_MAX_CHARS)

            val created =
                repository.addMessage(
                    sessionId = "main",
                    role = MessageRole.ToolResult,
                    content = longContent,
                    providerMeta = longProviderMeta,
                    toolCallId = longToolCallId,
                    taskRunId = longTaskRunId,
                )
            val raw = database.messageDao().getAllBySessionId("main").single()

            assertEquals(longContent.take(MESSAGE_CONTENT_MAX_CHARS), created.content)
            assertEquals(longProviderMeta.take(MESSAGE_PROVIDER_META_MAX_CHARS), created.providerMeta)
            assertEquals(longToolCallId.take(MESSAGE_REFERENCE_ID_MAX_CHARS), created.toolCallId)
            assertEquals(longTaskRunId.take(MESSAGE_REFERENCE_ID_MAX_CHARS), created.taskRunId)
            assertEquals(created.content, raw.content)
            assertEquals(created.providerMeta, raw.providerMeta)
            assertEquals(created.toolCallId, raw.toolCallId)
            assertEquals(created.taskRunId, raw.taskRunId)
        }

    @Test
    fun `message reads and search bound legacy oversized rows`() =
        runTest {
            val longTitle = "Legacy session " + "t".repeat(SESSION_TITLE_MAX_CHARS + 25)
            val longContent = "Legacy " + "c".repeat(MESSAGE_CONTENT_MAX_CHARS + 25) + "TAIL"
            val longProviderMeta = "m".repeat(MESSAGE_PROVIDER_META_MAX_CHARS + 25)
            val longToolCallId = "tool-".repeat(MESSAGE_REFERENCE_ID_MAX_CHARS)
            val longTaskRunId = "run-".repeat(MESSAGE_REFERENCE_ID_MAX_CHARS)
            database.sessionDao().insert(
                SessionEntity(
                    id = "legacy",
                    title = longTitle,
                    isMain = false,
                    createdAt = 2L,
                    updatedAt = 2L,
                    archivedAt = null,
                    summaryText = null,
                ),
            )
            database.messageDao().insertAll(
                listOf(
                    messageEntity(
                        id = "legacy-message",
                        sessionId = "legacy",
                        content = longContent,
                        providerMeta = longProviderMeta,
                        toolCallId = longToolCallId,
                        taskRunId = longTaskRunId,
                    ),
                ),
            )

            val all = repository.getMessages("legacy").single()
            val observed = repository.observeMessages("legacy").first().single()
            val recent = repository.getRecentMessages("legacy", limit = 1).single()
            val byId = requireNotNull(repository.getMessagesByIds(listOf("legacy-message"))["legacy-message"])
            val search = repository.searchMessages("Legacy", limit = 1).single()

            listOf(all, observed, recent, byId).forEach { message ->
                assertEquals(longContent.take(MESSAGE_CONTENT_MAX_CHARS), message.content)
                assertEquals(longProviderMeta.take(MESSAGE_PROVIDER_META_MAX_CHARS), message.providerMeta)
                assertEquals(longToolCallId.take(MESSAGE_REFERENCE_ID_MAX_CHARS), message.toolCallId)
                assertEquals(longTaskRunId.take(MESSAGE_REFERENCE_ID_MAX_CHARS), message.taskRunId)
            }
            assertEquals(longTitle.take(SESSION_TITLE_MAX_CHARS), search.sessionTitle)
            assertEquals(longContent.take(MESSAGE_CONTENT_MAX_CHARS), search.content)
            assertTrue(!search.content.contains("TAIL"))
        }
}

private fun messageEntity(
    id: String,
    sessionId: String,
    content: String,
    providerMeta: String? = null,
    toolCallId: String? = null,
    taskRunId: String? = null,
): MessageEntity =
    MessageEntity(
        id = id,
        sessionId = sessionId,
        role = "assistant",
        content = content,
        createdAt = 1L,
        providerMeta = providerMeta,
        toolCallId = toolCallId,
        taskRunId = taskRunId,
    )
