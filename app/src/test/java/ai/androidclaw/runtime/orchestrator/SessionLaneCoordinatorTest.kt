package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionLaneCoordinatorTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var messageRepository: MessageRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var laneCoordinator: SessionLaneCoordinator

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        database = buildTestDatabase(application)
        messageRepository = MessageRepository(database.messageDao())
        sessionRepository = SessionRepository(database.sessionDao())
        laneCoordinator = SessionLaneCoordinator()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `same-session writes are serialized without interleaving`() = runTest {
        val sessionId = sessionRepository.createSession("Lane test").id
        val writerA = async {
            laneCoordinator.withLane(sessionId) {
                messageRepository.addMessage(sessionId, MessageRole.User, "a1")
                delay(25)
                messageRepository.addMessage(sessionId, MessageRole.Assistant, "a2")
            }
        }
        val writerB = async {
            delay(5)
            laneCoordinator.withLane(sessionId) {
                messageRepository.addMessage(sessionId, MessageRole.User, "b1")
                messageRepository.addMessage(sessionId, MessageRole.Assistant, "b2")
            }
        }

        awaitAll(writerA, writerB)

        val messages = messageRepository.getRecentMessages(sessionId, limit = 10).asReversed()

        assertEquals(listOf("a1", "a2", "b1", "b2"), messages.map { it.content })
    }
}
