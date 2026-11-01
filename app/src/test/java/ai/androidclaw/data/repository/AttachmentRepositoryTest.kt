package ai.androidclaw.data.repository

import ai.androidclaw.data.attachments.AttachmentStore
import ai.androidclaw.data.attachments.StagedAttachment
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.AttachmentKind
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.feature.chat.ChatExportFormat
import ai.androidclaw.feature.chat.ChatExportFormatter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AttachmentRepositoryTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var root: File
    private lateinit var attachments: AttachmentRepository
    private lateinit var sessions: SessionRepository
    private lateinit var messages: MessageRepository

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        root = Files.createTempDirectory("attachments").toFile()
        attachments = AttachmentRepository(database.messageAttachmentDao(), AttachmentStore(root))
        sessions = SessionRepository(database.sessionDao(), attachments)
        messages = MessageRepository(database.messageDao(), attachments)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun `attached images load with their message in order and list in exports`() =
        runTest {
            val session = sessions.createSession("Photos")
            val message = messages.addMessage(session.id, MessageRole.User, "Look")
            attachments.attachToMessage(message.id, session.id, listOf(staged(session.id, "a", "first.jpg"), staged(session.id, "b", "second.jpg")))

            val observed = messages.observeMessages(session.id).first().single()
            assertEquals(listOf("first.jpg", "second.jpg"), observed.attachments.map { it.displayName })
            assertEquals(AttachmentKind.UserImage, observed.attachments.first().kind)
            assertEquals(
                listOf("a", "b"),
                messages
                    .getRecentMessages(session.id, 10)
                    .single()
                    .attachments
                    .map { it.id },
            )
            assertEquals(
                2,
                messages
                    .getMessagePage(session.id, 10, 0)
                    .single()
                    .attachments.size,
            )

            val export = ChatExportFormatter.buildExportPayload(session, messages.getMessages(session.id), ChatExportFormat.Markdown, Instant.EPOCH)
            assertTrue(export.content, export.content.contains("Attachments: first.jpg, second.jpg"))
        }

    @Test
    fun `deleting a message removes its files and deleting a session removes the folder`() =
        runTest {
            val session = sessions.createSession("Photos")
            val first = messages.addMessage(session.id, MessageRole.User, "one")
            val second = messages.addMessage(session.id, MessageRole.User, "two")
            val a = staged(session.id, "a", "a.jpg")
            val b = staged(session.id, "b", "b.jpg")
            attachments.attachToMessage(first.id, session.id, listOf(a))
            attachments.attachToMessage(second.id, session.id, listOf(b))

            assertTrue(messages.deleteMessage(first.id))
            assertFalse(attachments.file(a.relativePath).exists())
            assertFalse(attachments.thumbnailFile(a.relativePath).exists())
            assertTrue(attachments.file(b.relativePath).exists())
            assertTrue(attachments.getForMessages(listOf(first.id)).isEmpty())

            assertTrue(sessions.deleteSession(session.id))
            assertFalse(attachments.store.sessionDirectory(session.id).exists())
            assertTrue(attachments.getForMessages(listOf(second.id)).isEmpty())
        }

    @Test
    fun `discarding staged chips deletes their files`() =
        runTest {
            val chip = staged("s1", "c", "c.jpg")
            attachments.discardStaged(listOf(chip))
            assertFalse(attachments.file(chip.relativePath).exists())
        }

    private fun staged(
        sessionId: String,
        id: String,
        name: String,
    ): StagedAttachment {
        val relativePath = attachments.store.relativePathFor(sessionId, id)
        attachments.file(relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        attachments.thumbnailFile(relativePath).writeBytes(byteArrayOf(4))
        return StagedAttachment(id, sessionId, name, relativePath, "image/jpeg", 3, 40, 30)
    }
}
