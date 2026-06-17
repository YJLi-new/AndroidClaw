package ai.androidclaw.runtime.tools

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderOAuthCredential
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.EventLogEntity
import ai.androidclaw.data.db.entity.MessageEntity
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class BuiltInToolsTest {
    private val testClock = Clock.fixed(Instant.parse("2026-03-08T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var sessionRepository: SessionRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var eventLogRepository: EventLogRepository
    private lateinit var schedulerCoordinator: SchedulerCoordinator

    @Before
    fun setUp() =
        runTest {
            application = ApplicationProvider.getApplicationContext()
            WorkManagerTestInitHelper.initializeTestWorkManager(
                application,
                Configuration.Builder().build(),
            )
            database = buildTestDatabase(application)
            sessionRepository = SessionRepository(database.sessionDao())
            messageRepository = MessageRepository(database.messageDao())
            taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
            memoryRepository = MemoryRepository(database.memoryItemDao())
            settingsDataStore = SettingsDataStore(application)
            eventLogRepository = EventLogRepository(database.eventLogDao())
            schedulerCoordinator =
                SchedulerCoordinator(
                    application = application,
                    clock = testClock,
                    taskRepository = taskRepository,
                    eventLogRepository = eventLogRepository,
                )
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            settingsDataStore.setMemoryEnabled(false)
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            settingsDataStore.setMemoryEnabled(false)
            memoryRepository.clear(settingsDataStore.memorySettingsSnapshot().installUserId)
            database.close()
        }

    @Test
    fun `sessions list returns persisted sessions`() =
        runTest {
            sessionRepository.getOrCreateMainSession()
            sessionRepository.createSession("Project X")
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.list"),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals("2", result.payload["sessionCount"]?.jsonPrimitive?.content)
            assertEquals(
                listOf("Main session", "Project X"),
                result.payload["sessions"]
                    ?.jsonArray
                    ?.map {
                        it.jsonObject["title"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                    }?.sorted(),
            )
        }

    @Test
    fun `sessions create persists a new normal session`() =
        runTest {
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.create"),
                    arguments =
                        buildJsonObject {
                            put("title", "Feature planning")
                        },
                )

            assertTrue(result.success)
            assertEquals("Created session \"Feature planning\".", result.summary)
            val sessionId =
                result.payload["sessionId"]
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            val storedSession = sessionRepository.getSession(sessionId)
            assertNotNull(storedSession)
            assertEquals("Feature planning", storedSession?.title)
            assertFalse(storedSession?.isMain ?: true)
            assertFalse(storedSession?.archived ?: true)
        }

    @Test
    fun `sessions search returns active title matches only`() =
        runTest {
            val alpha = sessionRepository.createSession("Project Alpha")
            val atlas = sessionRepository.createSession("Project Atlas")
            sessionRepository.createSession("Beta notes")
            val archived = sessionRepository.createSession("Project Archived")
            sessionRepository.archiveSession(archived.id)
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "Project")
                            put("limit", 5)
                        },
                )

            assertTrue(result.success)
            assertEquals("2", result.payload["resultCount"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["activeOnly"]?.jsonPrimitive?.content)
            val matchedSessionIds =
                result.payload["sessions"]
                    ?.jsonArray
                    ?.map { item ->
                        item.jsonObject
                            .getValue("id")
                            .jsonPrimitive
                            .content
                    }.orEmpty()
                    .sorted()
            assertEquals(
                listOf(alpha.id, atlas.id).sorted(),
                matchedSessionIds,
            )
        }

    @Test
    fun `sessions stats returns aggregate session state`() =
        runTest {
            val main = sessionRepository.getOrCreateMainSession()
            val compacted = sessionRepository.createSession("Compacted session")
            val archived = sessionRepository.createSession("Archived session")
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = compacted.id,
                summaryText = "Compact summary",
                compactedUntilMessageId = "message-1",
            )
            sessionRepository.archiveSession(archived.id)
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "chat.sessions.stats", sessionId = main.id),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals("3", result.payload["sessionCount"]?.jsonPrimitive?.content)
            assertEquals("3", result.payload["totalSessionCount"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["activeSessionCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["archivedSessionCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["mainSessionCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["summarizedSessionCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["compactedSessionCount"]?.jsonPrimitive?.content)
            assertNotNull(result.payload["oldestSessionCreatedAtIso"])
            assertNotNull(result.payload["newestSessionUpdatedAtIso"])
            assertNotNull(result.payload["newestArchivedAtIso"])
        }

    @Test
    fun `sessions summaries lists summarized and compacted sessions`() =
        runTest {
            val longSummary = "s".repeat(600)
            database.sessionDao().insert(
                SessionEntity(
                    id = "plain",
                    title = "Plain session",
                    isMain = false,
                    createdAt = 1_000L,
                    updatedAt = 1_500L,
                    archivedAt = null,
                    summaryText = null,
                    compactedUntilMessageId = null,
                ),
            )
            database.sessionDao().insert(
                SessionEntity(
                    id = "summary",
                    title = "Summary session",
                    isMain = false,
                    createdAt = 2_000L,
                    updatedAt = 3_000L,
                    archivedAt = null,
                    summaryText = longSummary,
                    compactedUntilMessageId = null,
                ),
            )
            database.sessionDao().insert(
                SessionEntity(
                    id = "compact",
                    title = "Compacted session",
                    isMain = false,
                    createdAt = 3_000L,
                    updatedAt = 4_000L,
                    archivedAt = null,
                    summaryText = "Compact summary",
                    compactedUntilMessageId = "message-1",
                ),
            )
            database.sessionDao().insert(
                SessionEntity(
                    id = "archived",
                    title = "Archived summary",
                    isMain = false,
                    createdAt = 4_000L,
                    updatedAt = 5_000L,
                    archivedAt = 5_500L,
                    summaryText = "Archived summary",
                    compactedUntilMessageId = null,
                ),
            )
            val registry = buildRegistry()

            val active =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.compacted"),
                    arguments = buildJsonObject {},
                )
            val includeArchived =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.summaries"),
                    arguments =
                        buildJsonObject {
                            put("includeArchived", true)
                        },
                )

            assertTrue(active.success)
            assertEquals("2", active.payload["sessionCount"]?.jsonPrimitive?.content)
            val activeSessions =
                active.payload
                    .getValue("sessions")
                    .jsonArray
                    .map { session -> session.jsonObject }
            assertEquals(
                listOf("compact", "summary"),
                activeSessions.map { session -> session.getValue("sessionId").jsonPrimitive.content },
            )
            val summarySession = activeSessions.single { session -> session.getValue("sessionId").jsonPrimitive.content == "summary" }
            assertEquals("600", summarySession.getValue("summaryLength").jsonPrimitive.content)
            assertEquals(true.toString(), summarySession.getValue("summaryTruncated").jsonPrimitive.content)
            assertEquals(
                "500",
                summarySession
                    .getValue("summarySnippet")
                    .jsonPrimitive
                    .content
                    .length
                    .toString(),
            )
            val compactSession = activeSessions.first()
            assertEquals("message-1", compactSession.getValue("compactedUntilMessageId").jsonPrimitive.content)
            assertEquals(true.toString(), compactSession.getValue("compacted").jsonPrimitive.content)
            assertTrue(includeArchived.success)
            assertEquals("3", includeArchived.payload["sessionCount"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), includeArchived.payload["includeArchived"]?.jsonPrimitive?.content)
        }

    @Test
    fun `sessions summary update replaces summary while preserving compaction boundary`() =
        runTest {
            val session = sessionRepository.createSession("Summary update")
            val boundary =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Boundary answer",
                )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = session.id,
                summaryText = "Old summary",
                compactedUntilMessageId = boundary.id,
            )
            val longSummary = "u".repeat(4_050)
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.summary.set", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("summary", longSummary)
                        },
                )

            assertTrue(result.success)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("Old summary".length.toString(), result.payload["previousSummaryLength"]?.jsonPrimitive?.content)
            assertEquals("4000", result.payload["summaryLength"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["summaryTruncated"]?.jsonPrimitive?.content)
            assertEquals(longSummary.take(4_000), result.payload["summaryText"]?.jsonPrimitive?.content)
            assertEquals(boundary.id, result.payload["compactedUntilMessageId"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["compacted"]?.jsonPrimitive?.content)

            val updatedSession = requireNotNull(sessionRepository.getSession(session.id))
            assertEquals(longSummary.take(4_000), updatedSession.summaryText)
            assertEquals(boundary.id, updatedSession.compactedUntilMessageId)
        }

    @Test
    fun `sessions summary clear requires confirmation and clears compaction boundary`() =
        runTest {
            val session = sessionRepository.createSession("Clear summary")
            val boundary =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "Boundary prompt",
                )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = session.id,
                summaryText = "Summary to clear",
                compactedUntilMessageId = boundary.id,
            )
            val registry = buildRegistry()

            val denied =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.summary.clear", sessionId = session.id),
                    arguments = buildJsonObject {},
                )

            assertFalse(denied.success)
            assertEquals("CONFIRMATION_REQUIRED", denied.errorCode)
            assertEquals("Summary to clear", sessionRepository.getSession(session.id)?.summaryText)
            assertEquals(boundary.id, sessionRepository.getSession(session.id)?.compactedUntilMessageId)

            val cleared =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.summary.clear", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(cleared.success)
            assertEquals(session.id, cleared.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), cleared.payload["clearSummary"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), cleared.payload["summaryCleared"]?.jsonPrimitive?.content)
            assertEquals("16", cleared.payload["previousSummaryLength"]?.jsonPrimitive?.content)
            assertEquals("0", cleared.payload["summaryLength"]?.jsonPrimitive?.content)
            assertEquals(JsonNull, cleared.payload["summaryText"])
            assertEquals(boundary.id, cleared.payload["previousCompactedUntilMessageId"]?.jsonPrimitive?.content)
            assertEquals(JsonNull, cleared.payload["compactedUntilMessageId"])
            assertEquals(false.toString(), cleared.payload["compacted"]?.jsonPrimitive?.content)

            val updatedSession = requireNotNull(sessionRepository.getSession(session.id))
            assertNull(updatedSession.summaryText)
            assertNull(updatedSession.compactedUntilMessageId)
        }

    @Test
    fun `sessions activity lists recent sessions with latest message snippets`() =
        runTest {
            val longLatestMessage = "m".repeat(350)
            database.sessionDao().insert(
                SessionEntity(
                    id = "quiet",
                    title = "Quiet session",
                    isMain = false,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    archivedAt = null,
                    summaryText = null,
                    compactedUntilMessageId = null,
                ),
            )
            database.sessionDao().insert(
                SessionEntity(
                    id = "active",
                    title = "Active session",
                    isMain = false,
                    createdAt = 2_000L,
                    updatedAt = 3_000L,
                    archivedAt = null,
                    summaryText = "Activity summary",
                    compactedUntilMessageId = "active-latest",
                ),
            )
            database.sessionDao().insert(
                SessionEntity(
                    id = "archived",
                    title = "Archived activity",
                    isMain = false,
                    createdAt = 3_000L,
                    updatedAt = 7_000L,
                    archivedAt = 8_000L,
                    summaryText = null,
                    compactedUntilMessageId = null,
                ),
            )
            database.messageDao().insertAll(
                listOf(
                    toolTestMessageEntity(
                        id = "active-older",
                        sessionId = "active",
                        role = "user",
                        content = "Older prompt",
                        createdAt = 4_000L,
                    ),
                    toolTestMessageEntity(
                        id = "active-latest",
                        sessionId = "active",
                        role = "assistant",
                        content = longLatestMessage,
                        createdAt = 6_000L,
                    ),
                ),
            )
            val registry = buildRegistry()

            val active =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.timeline"),
                    arguments = buildJsonObject {},
                )
            val includeArchived =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.activity"),
                    arguments =
                        buildJsonObject {
                            put("includeArchived", true)
                            put("limit", 1)
                        },
                )

            assertTrue(active.success)
            assertEquals("2", active.payload["sessionCount"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), active.payload["includeArchived"]?.jsonPrimitive?.content)
            val activeSessions =
                active.payload
                    .getValue("sessions")
                    .jsonArray
                    .map { session -> session.jsonObject }
            assertEquals(
                listOf("active", "quiet"),
                activeSessions.map { session -> session.getValue("sessionId").jsonPrimitive.content },
            )
            val activeSession = activeSessions.first()
            assertEquals("2", activeSession.getValue("messageCount").jsonPrimitive.content)
            assertEquals(true.toString(), activeSession.getValue("hasSummary").jsonPrimitive.content)
            assertEquals(true.toString(), activeSession.getValue("compacted").jsonPrimitive.content)
            val latestMessage = activeSession.getValue("latestMessage").jsonObject
            assertEquals("active-latest", latestMessage.getValue("messageId").jsonPrimitive.content)
            assertEquals("Assistant", latestMessage.getValue("role").jsonPrimitive.content)
            assertEquals(
                "300",
                latestMessage
                    .getValue("contentSnippet")
                    .jsonPrimitive
                    .content
                    .length
                    .toString(),
            )
            assertEquals("350", latestMessage.getValue("contentLength").jsonPrimitive.content)
            assertEquals(true.toString(), latestMessage.getValue("contentTruncated").jsonPrimitive.content)

            assertTrue(includeArchived.success)
            assertEquals("1", includeArchived.payload["sessionCount"]?.jsonPrimitive?.content)
            val archivedSession =
                includeArchived.payload
                    .getValue("sessions")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("archived", archivedSession.getValue("sessionId").jsonPrimitive.content)
            assertEquals(true.toString(), archivedSession.getValue("archived").jsonPrimitive.content)
        }

    @Test
    fun `sessions get returns active session details and message count`() =
        runTest {
            val session = sessionRepository.createSession("Inspect me")
            val boundary =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "First message",
                )
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.Assistant,
                content = "Second message",
            )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = session.id,
                summaryText = "Compact summary",
                compactedUntilMessageId = boundary.id,
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.get", sessionId = session.id),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals("Loaded session \"Inspect me\".", result.summary)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("Inspect me", result.payload["title"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["messageCount"]?.jsonPrimitive?.content)
            assertEquals("Compact summary", result.payload["summaryText"]?.jsonPrimitive?.content)
            assertEquals(boundary.id, result.payload["compactedUntilMessageId"]?.jsonPrimitive?.content)
        }

    @Test
    fun `sessions fork duplicates messages and remaps compaction boundary`() =
        runTest {
            val sourceSession = sessionRepository.createSession("Source transcript")
            messageRepository.addMessage(
                sessionId = sourceSession.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "Plan the fork",
            )
            val boundary =
                messageRepository.addMessage(
                    sessionId = sourceSession.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Fork this answer",
                    toolCallId = "tool-1",
                )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = sourceSession.id,
                summaryText = "Source summary",
                compactedUntilMessageId = boundary.id,
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.duplicate", sessionId = sourceSession.id),
                    arguments =
                        buildJsonObject {
                            put("title", "Forked transcript")
                        },
                )

            assertTrue(result.success)
            assertEquals(sourceSession.id, result.payload["sourceSessionId"]?.jsonPrimitive?.content)
            assertEquals("Forked transcript", result.payload["title"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["sourceMessageCount"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["copiedMessageCount"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["summaryCopied"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["compactionBoundaryCopied"]?.jsonPrimitive?.content)
            val forkedSessionId =
                result.payload
                    .getValue("sessionId")
                    .jsonPrimitive
                    .content
            val forkedSession = requireNotNull(sessionRepository.getSession(forkedSessionId))
            val forkedMessages = messageRepository.getMessages(forkedSessionId)

            assertFalse(forkedSession.isMain)
            assertFalse(forkedSession.archived)
            assertEquals("Source summary", forkedSession.summaryText)
            assertEquals(
                listOf("Plan the fork", "Fork this answer"),
                forkedMessages.map { message -> message.content },
            )
            assertEquals("tool-1", forkedMessages.last().toolCallId)
            assertEquals(forkedMessages.last().id, forkedSession.compactedUntilMessageId)
            assertTrue(forkedMessages.none { message -> message.id == boundary.id })
        }

    @Test
    fun `sessions fork can create an empty fork without summary metadata`() =
        runTest {
            val sourceSession = sessionRepository.createSession("Empty fork source")
            messageRepository.addMessage(
                sessionId = sourceSession.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "Do not copy me",
            )
            sessionRepository.updateSummary(
                id = sourceSession.id,
                summaryText = "Do not copy summary",
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.fork", sessionId = sourceSession.id),
                    arguments =
                        buildJsonObject {
                            put("copyMessages", false)
                            put("copySummary", false)
                        },
                )

            assertTrue(result.success)
            assertEquals(false.toString(), result.payload["copyMessages"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), result.payload["copySummary"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["sourceMessageCount"]?.jsonPrimitive?.content)
            assertEquals("0", result.payload["messageCount"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), result.payload["summaryCopied"]?.jsonPrimitive?.content)
            val forkedSessionId =
                result.payload
                    .getValue("sessionId")
                    .jsonPrimitive
                    .content

            assertEquals(
                emptyList<ai.androidclaw.data.model.ChatMessage>(),
                messageRepository.getMessages(forkedSessionId),
            )
            assertEquals(null, sessionRepository.getSession(forkedSessionId)?.summaryText)
        }

    @Test
    fun `sessions merge copies messages into active target and can archive source`() =
        runTest {
            val sourceSession = sessionRepository.createSession("Merge source")
            val targetSession = sessionRepository.createSession("Merge target")
            messageRepository.addMessage(
                sessionId = sourceSession.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "Source prompt",
            )
            messageRepository.addMessage(
                sessionId = sourceSession.id,
                role = ai.androidclaw.data.model.MessageRole.Assistant,
                content = "Source answer",
            )
            messageRepository.addMessage(
                sessionId = targetSession.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "Target prompt",
            )
            val registry = buildRegistry()

            val denied =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.merge", sessionId = targetSession.id),
                    arguments =
                        buildJsonObject {
                            put("sourceSessionId", sourceSession.id)
                            put("archiveSource", true)
                        },
                )

            assertFalse(denied.success)
            assertEquals("CONFIRMATION_REQUIRED", denied.errorCode)
            assertEquals(1, messageRepository.getMessageCount(targetSession.id))
            assertFalse(sessionRepository.getSession(sourceSession.id)?.archived ?: true)

            val merged =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.merge", sessionId = targetSession.id),
                    arguments =
                        buildJsonObject {
                            put("fromSessionId", sourceSession.id)
                            put("archiveSource", true)
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(merged.success)
            assertEquals(sourceSession.id, merged.payload["sourceSessionId"]?.jsonPrimitive?.content)
            assertEquals(targetSession.id, merged.payload["targetSessionId"]?.jsonPrimitive?.content)
            assertEquals("2", merged.payload["sourceMessageCount"]?.jsonPrimitive?.content)
            assertEquals("2", merged.payload["copiedMessageCount"]?.jsonPrimitive?.content)
            assertEquals("3", merged.payload["targetMessageCount"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), merged.payload["copySummary"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), merged.payload["summaryCopied"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), merged.payload["archiveSource"]?.jsonPrimitive?.content)
            assertTrue(sessionRepository.getSession(sourceSession.id)?.archived == true)
            assertEquals(2, messageRepository.getMessageCount(sourceSession.id))
            val targetMessages = messageRepository.getMessages(targetSession.id)
            assertEquals(3, targetMessages.size)
            assertTrue(targetMessages.any { message -> message.content == "Source prompt" })
            assertTrue(targetMessages.any { message -> message.content == "Source answer" })
            assertTrue(targetMessages.any { message -> message.content == "Target prompt" })
        }

    @Test
    fun `sessions merge can copy summary and remap compaction boundary into target`() =
        runTest {
            val sourceSession = sessionRepository.createSession("Merge summarized source")
            val targetSession = sessionRepository.createSession("Merge summarized target")
            messageRepository.addMessage(
                sessionId = sourceSession.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "Summarized source prompt",
            )
            val boundary =
                messageRepository.addMessage(
                    sessionId = sourceSession.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Summarized source answer",
                    toolCallId = "tool-summary",
                )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = sourceSession.id,
                summaryText = "Merged source summary",
                compactedUntilMessageId = boundary.id,
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.combine"),
                    arguments =
                        buildJsonObject {
                            put("sourceSessionId", sourceSession.id)
                            put("intoSessionId", targetSession.id)
                            put("copySummary", true)
                        },
                )

            assertTrue(result.success)
            assertEquals("2", result.payload["copiedMessageCount"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["copySummary"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["summaryCopied"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["compactionBoundaryCopied"]?.jsonPrimitive?.content)
            val updatedTarget = requireNotNull(sessionRepository.getSession(targetSession.id))
            val targetMessages = messageRepository.getMessages(targetSession.id)
            assertEquals("Merged source summary", updatedTarget.summaryText)
            assertNotNull(updatedTarget.compactedUntilMessageId)
            assertTrue(updatedTarget.compactedUntilMessageId != boundary.id)
            assertEquals(
                targetMessages.single { message -> message.content == "Summarized source answer" }.id,
                updatedTarget.compactedUntilMessageId,
            )
            assertEquals("tool-summary", targetMessages.last().toolCallId)
        }

    @Test
    fun `sessions compare returns bounded transcript stats and recent snippets`() =
        runTest {
            val leftSession = sessionRepository.createSession("Compare left")
            val rightSession = sessionRepository.createSession("Compare right")
            val leftBoundary =
                messageRepository.addMessage(
                    sessionId = leftSession.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "Left prompt",
                )
            messageRepository.addMessage(
                sessionId = rightSession.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "Right prompt",
            )
            messageRepository.addMessage(
                sessionId = rightSession.id,
                role = ai.androidclaw.data.model.MessageRole.Assistant,
                content = "Right answer",
            )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = leftSession.id,
                summaryText = "Left compact summary",
                compactedUntilMessageId = leftBoundary.id,
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.diff", sessionId = leftSession.id),
                    arguments =
                        buildJsonObject {
                            put("rightSessionId", rightSession.id)
                            put("limit", 1)
                        },
                )

            assertTrue(result.success)
            assertEquals(leftSession.id, result.payload["leftSessionId"]?.jsonPrimitive?.content)
            assertEquals(rightSession.id, result.payload["rightSessionId"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["messageCountDeltaRightMinusLeft"]?.jsonPrimitive?.content)
            val left = result.payload.getValue("left").jsonObject
            val right = result.payload.getValue("right").jsonObject
            assertEquals("Compare left", left["title"]?.jsonPrimitive?.content)
            assertEquals("1", left["messageCount"]?.jsonPrimitive?.content)
            assertEquals("Left compact summary", left["summarySnippet"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), left["compacted"]?.jsonPrimitive?.content)
            assertEquals(leftBoundary.id, left["compactedUntilMessageId"]?.jsonPrimitive?.content)
            assertEquals("Compare right", right["title"]?.jsonPrimitive?.content)
            assertEquals("2", right["messageCount"]?.jsonPrimitive?.content)
            assertEquals(
                "Right answer",
                right
                    .getValue("recentMessages")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("contentSnippet")
                    .jsonPrimitive
                    .content,
            )
            val rightRoles =
                right
                    .getValue("roleStats")
                    .jsonArray
                    .map { roleStats ->
                        roleStats.jsonObject
                            .getValue("role")
                            .jsonPrimitive
                            .content
                    }.sorted()
            assertEquals(listOf("Assistant", "User"), rightRoles)
        }

    @Test
    fun `sessions compare rejects same session`() =
        runTest {
            val session = sessionRepository.createSession("Compare same")
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.compare", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("rightSessionId", session.id)
                        },
                )

            assertFalse(result.success)
            assertEquals("INVALID_TARGET_SESSION", result.errorCode)
        }

    @Test
    fun `sessions clear requires confirmation and clears transcript while preserving session`() =
        runTest {
            val session = sessionRepository.createSession("Clear transcript")
            val boundary =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "Remove this prompt",
                )
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.Assistant,
                content = "Remove this answer",
            )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = session.id,
                summaryText = "Keep this summary",
                compactedUntilMessageId = boundary.id,
            )
            val registry = buildRegistry()

            val denied =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.clear", sessionId = session.id),
                    arguments = buildJsonObject {},
                )

            assertFalse(denied.success)
            assertEquals("CONFIRMATION_REQUIRED", denied.errorCode)
            assertEquals("2", messageRepository.getMessageCount(session.id).toString())

            val cleared =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.clear", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(cleared.success)
            assertEquals(session.id, cleared.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("2", cleared.payload["deletedMessageCount"]?.jsonPrimitive?.content)
            assertEquals("0", cleared.payload["messageCount"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), cleared.payload["clearSummary"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), cleared.payload["summaryPreserved"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), cleared.payload["previousCompacted"]?.jsonPrimitive?.content)
            assertEquals(JsonNull, cleared.payload["compactedUntilMessageId"])
            assertEquals(emptyList<ai.androidclaw.data.model.ChatMessage>(), messageRepository.getMessages(session.id))
            val updatedSession = requireNotNull(sessionRepository.getSession(session.id))
            assertEquals("Clear transcript", updatedSession.title)
            assertEquals("Keep this summary", updatedSession.summaryText)
            assertEquals(null, updatedSession.compactedUntilMessageId)
        }

    @Test
    fun `sessions clear can also clear summary after confirmation`() =
        runTest {
            val session = sessionRepository.createSession("Clear all transcript metadata")
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "Remove me",
            )
            sessionRepository.updateSummary(
                id = session.id,
                summaryText = "Remove this summary too",
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.messages.clear"),
                    arguments =
                        buildJsonObject {
                            put("sessionId", session.id)
                            put("clearSummary", true)
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(result.success)
            assertEquals("1", result.payload["deletedMessageCount"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["clearSummary"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), result.payload["summaryPreserved"]?.jsonPrimitive?.content)
            assertEquals("0", result.payload["summaryLength"]?.jsonPrimitive?.content)
            assertEquals(0, messageRepository.getMessageCount(session.id))
            assertEquals(null, sessionRepository.getSession(session.id)?.summaryText)
        }

    @Test
    fun `sessions delete requires confirmation and removes normal session with transcript`() =
        runTest {
            val session = sessionRepository.createSession("Delete me")
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "Delete this message too",
            )
            sessionRepository.updateSummary(
                id = session.id,
                summaryText = "Delete this summary",
            )
            val registry = buildRegistry()

            val denied =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.delete", sessionId = session.id),
                    arguments = buildJsonObject {},
                )

            assertFalse(denied.success)
            assertEquals("CONFIRMATION_REQUIRED", denied.errorCode)
            assertNotNull(sessionRepository.getSession(session.id))
            assertEquals(1, messageRepository.getMessageCount(session.id))

            val deleted =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.remove", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(deleted.success)
            assertEquals(session.id, deleted.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("Delete me", deleted.payload["title"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), deleted.payload["deleted"]?.jsonPrimitive?.content)
            assertEquals("1", deleted.payload["deletedMessageCount"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), deleted.payload["hadSummary"]?.jsonPrimitive?.content)
            assertEquals(null, sessionRepository.getSession(session.id))
            assertEquals(0, messageRepository.getMessageCount(session.id))
        }

    @Test
    fun `sessions delete rejects the main session`() =
        runTest {
            val mainSession = sessionRepository.getOrCreateMainSession()
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.delete", sessionId = mainSession.id),
                    arguments =
                        buildJsonObject {
                            put("confirm", "CONFIRM")
                        },
                )

            assertFalse(result.success)
            assertEquals("MAIN_SESSION", result.errorCode)
            assertNotNull(sessionRepository.getSession(mainSession.id))
        }

    @Test
    fun `sessions archive hides session until list includes archived and unarchive restores it`() =
        runTest {
            sessionRepository.getOrCreateMainSession()
            val session = sessionRepository.createSession("Project archive")
            val registry = buildRegistry()

            val archived =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.archive", sessionId = session.id),
                    arguments = buildJsonObject {},
                )
            val activeList =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.list"),
                    arguments = buildJsonObject {},
                )
            val archivedList =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.list"),
                    arguments =
                        buildJsonObject {
                            put("includeArchived", true)
                        },
                )
            assertTrue(archived.success)
            assertEquals(true.toString(), archived.payload["archived"]?.jsonPrimitive?.content)
            assertTrue(sessionRepository.getSession(session.id)?.archived == true)
            assertFalse(
                activeList.payload["sessions"]
                    ?.jsonArray
                    ?.any { item -> item.jsonObject["id"]?.jsonPrimitive?.content == session.id }
                    ?: true,
            )
            assertTrue(
                archivedList.payload["sessions"]
                    ?.jsonArray
                    ?.any { item -> item.jsonObject["id"]?.jsonPrimitive?.content == session.id }
                    ?: false,
            )
            val restored =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.unarchive"),
                    arguments =
                        buildJsonObject {
                            put("sessionId", session.id)
                        },
                )

            assertTrue(restored.success)
            assertEquals(false.toString(), restored.payload["archived"]?.jsonPrimitive?.content)
            assertTrue(sessionRepository.getSession(session.id)?.archived == false)
        }

    @Test
    fun `sessions archive rejects the main session`() =
        runTest {
            val mainSession = sessionRepository.getOrCreateMainSession()
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.archive", sessionId = mainSession.id),
                    arguments = buildJsonObject {},
                )

            assertFalse(result.success)
            assertEquals("MAIN_SESSION", result.errorCode)
            assertTrue(sessionRepository.getSession(mainSession.id)?.archived == false)
        }

    @Test
    fun `sessions rename updates active session title`() =
        runTest {
            val session = sessionRepository.createSession("Untitled project")
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.rename", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("title", "Roadmap planning")
                        },
                )

            assertTrue(result.success)
            assertEquals("Renamed session to \"Roadmap planning\".", result.summary)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("Untitled project", result.payload["previousTitle"]?.jsonPrimitive?.content)
            assertEquals("Roadmap planning", result.payload["title"]?.jsonPrimitive?.content)
            assertEquals("Roadmap planning", sessionRepository.getSession(session.id)?.title)
        }

    @Test
    fun `sessions rename can target an explicit session id`() =
        runTest {
            val activeSession = sessionRepository.createSession("Active chat")
            val targetSession = sessionRepository.createSession("Target chat")
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.rename", sessionId = activeSession.id),
                    arguments =
                        buildJsonObject {
                            put("sessionId", targetSession.id)
                            put("title", "Renamed target")
                        },
                )

            assertTrue(result.success)
            assertEquals(targetSession.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("Active chat", sessionRepository.getSession(activeSession.id)?.title)
            assertEquals("Renamed target", sessionRepository.getSession(targetSession.id)?.title)
        }

    @Test
    fun `sessions rename requires an active or explicit session`() =
        runTest {
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.rename"),
                    arguments =
                        buildJsonObject {
                            put("title", "No target")
                        },
                )

            assertFalse(result.success)
            assertEquals("MISSING_SESSION", result.errorCode)
        }

    @Test
    fun `messages search returns active message matches only`() =
        runTest {
            val activeSession = sessionRepository.createSession("Active transcript")
            val archivedSession = sessionRepository.createSession("Archived transcript")
            val activeMessage =
                messageRepository.addMessage(
                    sessionId = activeSession.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "Remember the orchid deployment note.",
                )
            messageRepository.addMessage(
                sessionId = activeSession.id,
                role = ai.androidclaw.data.model.MessageRole.Assistant,
                content = "No matching content here.",
            )
            messageRepository.addMessage(
                sessionId = archivedSession.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "Archived orchid note should stay hidden.",
            )
            sessionRepository.archiveSession(archivedSession.id)
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "chat.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "orchid")
                            put("limit", 5)
                        },
                )

            assertTrue(result.success)
            assertEquals("1", result.payload["resultCount"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["activeSessionsOnly"]?.jsonPrimitive?.content)
            val message =
                result.payload
                    .getValue("messages")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(activeMessage.id, message.getValue("messageId").jsonPrimitive.content)
            assertEquals(activeSession.id, message.getValue("sessionId").jsonPrimitive.content)
            assertEquals("User", message.getValue("role").jsonPrimitive.content)
            assertEquals(
                "Remember the orchid deployment note.",
                message.getValue("contentSnippet").jsonPrimitive.content,
            )
        }

    @Test
    fun `messages create appends active session message with references`() =
        runTest {
            val session = sessionRepository.createSession("Append active")
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "message.add", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("role", "tool_result")
                            put("content", "Created tool result.")
                            put("toolCallId", "tool-call-created")
                            put("taskRunId", "task-run-created")
                        },
                )

            assertTrue(result.success)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("ToolResult", result.payload["role"]?.jsonPrimitive?.content)
            assertEquals("Created tool result.", result.payload["contentSnippet"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), result.payload["hasProviderMeta"]?.jsonPrimitive?.content)
            assertEquals("tool-call-created", result.payload["toolCallId"]?.jsonPrimitive?.content)
            assertEquals("task-run-created", result.payload["taskRunId"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["messageCount"]?.jsonPrimitive?.content)
            val messageId =
                result.payload
                    .getValue("messageId")
                    .jsonPrimitive
                    .content
            val storedMessage = messageRepository.getMessage(messageId)
            assertNotNull(storedMessage)
            assertEquals(session.id, storedMessage?.sessionId)
            assertEquals(ai.androidclaw.data.model.MessageRole.ToolResult, storedMessage?.role)
            assertEquals("Created tool result.", storedMessage?.content)
            assertEquals("tool-call-created", storedMessage?.toolCallId)
            assertEquals("task-run-created", storedMessage?.taskRunId)
        }

    @Test
    fun `messages create targets explicit session and rejects invalid role`() =
        runTest {
            val session = sessionRepository.createSession("Append explicit")
            val registry = buildRegistry()

            val created =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "chat.append"),
                    arguments =
                        buildJsonObject {
                            put("sessionId", session.id)
                            put("role", "assistant")
                            put("text", "Created assistant reply.")
                        },
                )

            assertTrue(created.success)
            assertEquals(session.id, created.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("Assistant", created.payload["role"]?.jsonPrimitive?.content)
            assertEquals("Created assistant reply.", created.payload["contentSnippet"]?.jsonPrimitive?.content)
            assertEquals(1, messageRepository.getMessageCount(session.id))

            val rejected =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "chat.message.create", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("role", "invalid")
                            put("content", "Should not append.")
                        },
                )

            assertFalse(rejected.success)
            assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
            assertEquals("role", rejected.payload["field"]?.jsonPrimitive?.content)
            assertEquals(1, messageRepository.getMessageCount(session.id))
        }

    @Test
    fun `messages get returns one message by id with session metadata`() =
        runTest {
            val session = sessionRepository.createSession("Message details")
            val message =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.ToolCall,
                    content = "Tool result body",
                    providerMeta = """{"providerId":"fake"}""",
                    toolCallId = "tool-call-1",
                    taskRunId = "task-run-1",
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "message.get"),
                    arguments =
                        buildJsonObject {
                            put("messageId", message.id)
                        },
                )

            assertTrue(result.success)
            assertEquals(message.id, result.payload["messageId"]?.jsonPrimitive?.content)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("Message details", result.payload["sessionTitle"]?.jsonPrimitive?.content)
            assertEquals("ToolCall", result.payload["role"]?.jsonPrimitive?.content)
            assertEquals("Tool result body", result.payload["contentSnippet"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["hasProviderMeta"]?.jsonPrimitive?.content)
            assertEquals("tool-call-1", result.payload["toolCallId"]?.jsonPrimitive?.content)
            assertEquals("task-run-1", result.payload["taskRunId"]?.jsonPrimitive?.content)
        }

    @Test
    fun `messages copy copies message into active target session preserving metadata`() =
        runTest {
            val sourceSession = sessionRepository.createSession("Source transcript")
            val targetSession = sessionRepository.createSession("Target transcript")
            val sourceMessage =
                messageRepository.addMessage(
                    sessionId = sourceSession.id,
                    role = ai.androidclaw.data.model.MessageRole.ToolCall,
                    content = "Copy this tool call.",
                    providerMeta = """{"providerId":"fake"}""",
                    toolCallId = "tool-call-copy",
                    taskRunId = "task-run-copy",
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "message.copy", sessionId = targetSession.id),
                    arguments =
                        buildJsonObject {
                            put("messageId", sourceMessage.id)
                        },
                )

            assertTrue(result.success)
            assertEquals(sourceMessage.id, result.payload["sourceMessageId"]?.jsonPrimitive?.content)
            assertEquals(sourceSession.id, result.payload["sourceSessionId"]?.jsonPrimitive?.content)
            assertEquals(targetSession.id, result.payload["targetSessionId"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), result.payload["sameSession"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["copyProviderMeta"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["copiedProviderMeta"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["copyReferences"]?.jsonPrimitive?.content)
            assertEquals("tool-call-copy", result.payload["toolCallId"]?.jsonPrimitive?.content)
            assertEquals("task-run-copy", result.payload["taskRunId"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["targetMessageCount"]?.jsonPrimitive?.content)
            val copiedMessageId =
                result.payload
                    .getValue("copiedMessageId")
                    .jsonPrimitive
                    .content
            assertTrue(copiedMessageId != sourceMessage.id)
            val copiedMessage = messageRepository.getMessage(copiedMessageId)
            assertNotNull(copiedMessage)
            assertEquals(targetSession.id, copiedMessage?.sessionId)
            assertEquals(sourceMessage.role, copiedMessage?.role)
            assertEquals(sourceMessage.content, copiedMessage?.content)
            assertEquals(sourceMessage.providerMeta, copiedMessage?.providerMeta)
            assertEquals(sourceMessage.toolCallId, copiedMessage?.toolCallId)
            assertEquals(sourceMessage.taskRunId, copiedMessage?.taskRunId)
            assertEquals(1, messageRepository.getMessageCount(sourceSession.id))
            assertEquals(1, messageRepository.getMessageCount(targetSession.id))
        }

    @Test
    fun `messages copy duplicates in place and can omit metadata references`() =
        runTest {
            val session = sessionRepository.createSession("Duplicate in place")
            val sourceMessage =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.ToolResult,
                    content = "Duplicate without provenance.",
                    providerMeta = """{"providerId":"fake"}""",
                    toolCallId = "tool-call-omit",
                    taskRunId = "task-run-omit",
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "messages.duplicate"),
                    arguments =
                        buildJsonObject {
                            put("id", sourceMessage.id)
                            put("copyProviderMeta", false)
                            put("copyReferences", false)
                        },
                )

            assertTrue(result.success)
            assertEquals(session.id, result.payload["sourceSessionId"]?.jsonPrimitive?.content)
            assertEquals(session.id, result.payload["targetSessionId"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["sameSession"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), result.payload["copyProviderMeta"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), result.payload["copiedProviderMeta"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), result.payload["copyReferences"]?.jsonPrimitive?.content)
            assertEquals(JsonNull, result.payload["toolCallId"])
            assertEquals(JsonNull, result.payload["taskRunId"])
            assertEquals("2", result.payload["targetMessageCount"]?.jsonPrimitive?.content)
            val copiedMessageId =
                result.payload
                    .getValue("copiedMessageId")
                    .jsonPrimitive
                    .content
            assertTrue(copiedMessageId != sourceMessage.id)
            val copiedMessage = messageRepository.getMessage(copiedMessageId)
            assertNotNull(copiedMessage)
            assertEquals(session.id, copiedMessage?.sessionId)
            assertEquals(sourceMessage.role, copiedMessage?.role)
            assertEquals(sourceMessage.content, copiedMessage?.content)
            assertNull(copiedMessage?.providerMeta)
            assertNull(copiedMessage?.toolCallId)
            assertNull(copiedMessage?.taskRunId)
            assertEquals(2, messageRepository.getMessageCount(session.id))
        }

    @Test
    fun `messages move requires confirmation and moves message into active target session`() =
        runTest {
            val sourceSession = sessionRepository.createSession("Move source")
            val targetSession = sessionRepository.createSession("Move target")
            val sourceMessage =
                messageRepository.addMessage(
                    sessionId = sourceSession.id,
                    role = ai.androidclaw.data.model.MessageRole.ToolCall,
                    content = "Move this tool call.",
                    providerMeta = """{"providerId":"fake"}""",
                    toolCallId = "tool-call-move",
                    taskRunId = "task-run-move",
                )
            val registry = buildRegistry()

            val denied =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "message.move", sessionId = targetSession.id),
                    arguments =
                        buildJsonObject {
                            put("messageId", sourceMessage.id)
                        },
                )

            assertFalse(denied.success)
            assertEquals("CONFIRMATION_REQUIRED", denied.errorCode)
            assertNotNull(messageRepository.getMessage(sourceMessage.id))
            assertEquals(1, messageRepository.getMessageCount(sourceSession.id))
            assertEquals(0, messageRepository.getMessageCount(targetSession.id))

            val moved =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "messages.move", sessionId = targetSession.id),
                    arguments =
                        buildJsonObject {
                            put("id", sourceMessage.id)
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(moved.success)
            assertEquals(sourceMessage.id, moved.payload["sourceMessageId"]?.jsonPrimitive?.content)
            assertEquals(sourceSession.id, moved.payload["sourceSessionId"]?.jsonPrimitive?.content)
            assertEquals(targetSession.id, moved.payload["targetSessionId"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), moved.payload["sourceMessageDeleted"]?.jsonPrimitive?.content)
            assertEquals("0", moved.payload["sourceMessageCount"]?.jsonPrimitive?.content)
            assertEquals("1", moved.payload["targetMessageCount"]?.jsonPrimitive?.content)
            assertEquals("tool-call-move", moved.payload["toolCallId"]?.jsonPrimitive?.content)
            assertEquals("task-run-move", moved.payload["taskRunId"]?.jsonPrimitive?.content)
            val movedMessageId =
                moved.payload
                    .getValue("movedMessageId")
                    .jsonPrimitive
                    .content
            assertTrue(movedMessageId != sourceMessage.id)
            assertNull(messageRepository.getMessage(sourceMessage.id))
            val movedMessage = messageRepository.getMessage(movedMessageId)
            assertNotNull(movedMessage)
            assertEquals(targetSession.id, movedMessage?.sessionId)
            assertEquals(sourceMessage.role, movedMessage?.role)
            assertEquals(sourceMessage.content, movedMessage?.content)
            assertEquals(sourceMessage.providerMeta, movedMessage?.providerMeta)
            assertEquals(sourceMessage.toolCallId, movedMessage?.toolCallId)
            assertEquals(sourceMessage.taskRunId, movedMessage?.taskRunId)
        }

    @Test
    fun `messages move clears source compaction boundary while preserving summary`() =
        runTest {
            val sourceSession = sessionRepository.createSession("Move compacted source")
            val targetSession = sessionRepository.createSession("Move compacted target")
            val boundary =
                messageRepository.addMessage(
                    sessionId = sourceSession.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "Move compacted prompt.",
                )
            val survivor =
                messageRepository.addMessage(
                    sessionId = sourceSession.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Keep this source response.",
                )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = sourceSession.id,
                summaryText = "Earlier summary",
                compactedUntilMessageId = boundary.id,
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "message.transfer"),
                    arguments =
                        buildJsonObject {
                            put("messageId", boundary.id)
                            put("targetSessionId", targetSession.id)
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(result.success)
            assertEquals(true.toString(), result.payload["wasCompactionBoundary"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["sourceCompactionBoundaryCleared"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["sourceSummaryPreserved"]?.jsonPrimitive?.content)
            assertEquals(JsonNull, result.payload["sourceCompactedUntilMessageId"])
            assertEquals("1", result.payload["sourceMessageCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["targetMessageCount"]?.jsonPrimitive?.content)
            val updatedSourceSession = sessionRepository.getSession(sourceSession.id)
            assertEquals("Earlier summary", updatedSourceSession?.summaryText)
            assertNull(updatedSourceSession?.compactedUntilMessageId)
            assertNull(messageRepository.getMessage(boundary.id))
            assertNotNull(messageRepository.getMessage(survivor.id))
            val movedMessageId =
                result.payload
                    .getValue("movedMessageId")
                    .jsonPrimitive
                    .content
            val movedMessage = messageRepository.getMessage(movedMessageId)
            assertNotNull(movedMessage)
            assertEquals(targetSession.id, movedMessage?.sessionId)
            assertEquals("Move compacted prompt.", movedMessage?.content)
        }

    @Test
    fun `messages update requires confirmation and replaces only target content`() =
        runTest {
            val session = sessionRepository.createSession("Edit one message")
            val target =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "Original draft.",
                )
            val survivor =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Keep this reply.",
                )
            val registry = buildRegistry()

            val denied =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "message.edit"),
                    arguments =
                        buildJsonObject {
                            put("messageId", target.id)
                            put("content", "Corrected draft.")
                        },
                )

            assertFalse(denied.success)
            assertEquals("CONFIRMATION_REQUIRED", denied.errorCode)
            assertEquals("Original draft.", messageRepository.getMessage(target.id)?.content)

            val updated =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "messages.update"),
                    arguments =
                        buildJsonObject {
                            put("id", target.id)
                            put("text", "Corrected draft.")
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(updated.success)
            assertEquals(target.id, updated.payload["messageId"]?.jsonPrimitive?.content)
            assertEquals(session.id, updated.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("User", updated.payload["role"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), updated.payload["updated"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), updated.payload["contentChanged"]?.jsonPrimitive?.content)
            assertEquals("Original draft.", updated.payload["previousContentSnippet"]?.jsonPrimitive?.content)
            assertEquals("Corrected draft.", updated.payload["contentSnippet"]?.jsonPrimitive?.content)
            assertEquals("Corrected draft.", messageRepository.getMessage(target.id)?.content)
            assertEquals("Keep this reply.", messageRepository.getMessage(survivor.id)?.content)
            assertEquals(2, messageRepository.getMessageCount(session.id))
        }

    @Test
    fun `messages update preserves metadata and compaction boundary`() =
        runTest {
            val session = sessionRepository.createSession("Edit boundary")
            val boundary =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.ToolResult,
                    content = "Original compacted result.",
                    providerMeta = """{"providerId":"fake"}""",
                    toolCallId = "tool-call-boundary",
                    taskRunId = "task-run-boundary",
                )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = session.id,
                summaryText = "Earlier summary",
                compactedUntilMessageId = boundary.id,
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "chat.message.update"),
                    arguments =
                        buildJsonObject {
                            put("messageId", boundary.id)
                            put("content", "Corrected compacted result.")
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(result.success)
            assertEquals("ToolResult", result.payload["role"]?.jsonPrimitive?.content)
            assertEquals("Corrected compacted result.", result.payload["contentSnippet"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["hasProviderMeta"]?.jsonPrimitive?.content)
            assertEquals("tool-call-boundary", result.payload["toolCallId"]?.jsonPrimitive?.content)
            assertEquals("task-run-boundary", result.payload["taskRunId"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["wasCompactionBoundary"]?.jsonPrimitive?.content)
            assertEquals(boundary.id, result.payload["compactedUntilMessageId"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["summaryPreserved"]?.jsonPrimitive?.content)
            val updatedMessage = messageRepository.getMessage(boundary.id)
            val updatedSession = sessionRepository.getSession(session.id)
            assertEquals("Corrected compacted result.", updatedMessage?.content)
            assertEquals("""{"providerId":"fake"}""", updatedMessage?.providerMeta)
            assertEquals("tool-call-boundary", updatedMessage?.toolCallId)
            assertEquals("task-run-boundary", updatedMessage?.taskRunId)
            assertEquals("Earlier summary", updatedSession?.summaryText)
            assertEquals(boundary.id, updatedSession?.compactedUntilMessageId)
        }

    @Test
    fun `messages delete requires confirmation and deletes only the target message`() =
        runTest {
            val session = sessionRepository.createSession("Delete one message")
            val target =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "Delete this draft.",
                )
            val survivor =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Keep this response.",
                )
            val registry = buildRegistry()

            val denied =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "message.remove"),
                    arguments =
                        buildJsonObject {
                            put("messageId", target.id)
                        },
                )

            assertFalse(denied.success)
            assertEquals("CONFIRMATION_REQUIRED", denied.errorCode)
            assertEquals(2, messageRepository.getMessageCount(session.id))
            assertNotNull(messageRepository.getMessage(target.id))

            val deleted =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "messages.delete"),
                    arguments =
                        buildJsonObject {
                            put("id", target.id)
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(deleted.success)
            assertEquals(target.id, deleted.payload["messageId"]?.jsonPrimitive?.content)
            assertEquals(session.id, deleted.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("User", deleted.payload["role"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), deleted.payload["deleted"]?.jsonPrimitive?.content)
            assertEquals("1", deleted.payload["messageCount"]?.jsonPrimitive?.content)
            assertEquals("Delete this draft.", deleted.payload["contentSnippet"]?.jsonPrimitive?.content)
            assertNull(messageRepository.getMessage(target.id))
            assertNotNull(messageRepository.getMessage(survivor.id))
            assertEquals(1, messageRepository.getMessageCount(session.id))
        }

    @Test
    fun `messages delete clears compaction boundary while preserving summary`() =
        runTest {
            val session = sessionRepository.createSession("Delete boundary")
            val boundary =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "Earlier compacted prompt.",
                )
            val survivor =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Visible response.",
                )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = session.id,
                summaryText = "Earlier summary",
                compactedUntilMessageId = boundary.id,
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "message.delete"),
                    arguments =
                        buildJsonObject {
                            put("messageId", boundary.id)
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(result.success)
            assertEquals(true.toString(), result.payload["wasCompactionBoundary"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["compactionBoundaryCleared"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["summaryPreserved"]?.jsonPrimitive?.content)
            assertEquals(boundary.id, result.payload["previousCompactedUntilMessageId"]?.jsonPrimitive?.content)
            assertEquals(JsonNull, result.payload["compactedUntilMessageId"])
            val updatedSession = sessionRepository.getSession(session.id)
            assertNotNull(updatedSession)
            assertEquals("Earlier summary", updatedSession?.summaryText)
            assertNull(updatedSession?.compactedUntilMessageId)
            assertNull(messageRepository.getMessage(boundary.id))
            assertNotNull(messageRepository.getMessage(survivor.id))
        }

    @Test
    fun `messages page returns bounded chronological transcript pages`() =
        runTest {
            val session = sessionRepository.createSession("Paged transcript")
            val first =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "First prompt",
                )
            val second =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Second answer",
                )
            val third =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.ToolResult,
                    content = "Third tool result",
                    providerMeta = """{"provider":"fake"}""",
                    toolCallId = "tool-1",
                )
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.Assistant,
                content = "Fourth answer",
            )
            val otherSession = sessionRepository.createSession("Other paged transcript")
            val otherMessage =
                messageRepository.addMessage(
                    sessionId = otherSession.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "Other prompt",
                )
            val registry = buildRegistry()

            val start =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "chat.transcript", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("limit", 2)
                        },
                )
            val after =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "messages.page", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("direction", "after")
                            put("anchorMessageId", second.id)
                            put("limit", 1)
                        },
                )
            val recent =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "messages.page", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("direction", "recent")
                            put("limit", 2)
                        },
                )
            val invalidAnchor =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "messages.page", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("direction", "before")
                            put("anchorMessageId", otherMessage.id)
                        },
                )

            assertTrue(start.success)
            assertEquals(session.id, start.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("start", start.payload["direction"]?.jsonPrimitive?.content)
            assertEquals("4", start.payload["messageCount"]?.jsonPrimitive?.content)
            assertEquals("2", start.payload["returnedCount"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), start.payload["chronological"]?.jsonPrimitive?.content)
            assertEquals(
                listOf(first.id, second.id),
                start.payload
                    .getValue("messages")
                    .jsonArray
                    .map { message ->
                        message.jsonObject
                            .getValue("messageId")
                            .jsonPrimitive
                            .content
                    },
            )

            assertTrue(after.success)
            val afterMessage =
                after.payload
                    .getValue("messages")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(third.id, afterMessage.getValue("messageId").jsonPrimitive.content)
            assertEquals("ToolResult", afterMessage.getValue("role").jsonPrimitive.content)
            assertEquals(true.toString(), afterMessage.getValue("hasProviderMeta").jsonPrimitive.content)
            assertEquals("tool-1", afterMessage.getValue("toolCallId").jsonPrimitive.content)

            assertTrue(recent.success)
            assertEquals(
                listOf("Third tool result", "Fourth answer"),
                recent.payload
                    .getValue("messages")
                    .jsonArray
                    .map { message ->
                        message.jsonObject
                            .getValue("contentSnippet")
                            .jsonPrimitive
                            .content
                    },
            )

            assertFalse(invalidAnchor.success)
            assertEquals("INVALID_PAGE_ANCHOR", invalidAnchor.errorCode)
        }

    @Test
    fun `messages context returns bounded window around message id`() =
        runTest {
            val session = sessionRepository.createSession("Context transcript")
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.System,
                content = "System preface",
            )
            val before =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "Question before anchor",
                )
            val anchor =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Anchor answer",
                    providerMeta = """{"providerId":"fake"}""",
                )
            val after =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.ToolResult,
                    content = "Tool result after anchor",
                    toolCallId = "tool-call-2",
                )
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.Assistant,
                content = "Later answer outside radius",
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "chat.context"),
                    arguments =
                        buildJsonObject {
                            put("messageId", anchor.id)
                            put("radius", 1)
                        },
                )
            val missing =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "messages.context"),
                    arguments =
                        buildJsonObject {
                            put("messageId", "missing-message")
                        },
                )

            assertTrue(result.success)
            assertEquals(anchor.id, result.payload["messageId"]?.jsonPrimitive?.content)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("Context transcript", result.payload["sessionTitle"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["beforeCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["afterCount"]?.jsonPrimitive?.content)
            assertEquals("3", result.payload["returnedCount"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["chronological"]?.jsonPrimitive?.content)
            val messages =
                result.payload
                    .getValue("messages")
                    .jsonArray
                    .map { message -> message.jsonObject }
            assertEquals(listOf(before.id, anchor.id, after.id), messages.map { message -> message.getValue("messageId").jsonPrimitive.content })
            assertEquals(listOf("before", "anchor", "after"), messages.map { message -> message.getValue("relativePosition").jsonPrimitive.content })
            assertEquals(true.toString(), messages[1].getValue("anchor").jsonPrimitive.content)
            assertEquals("Assistant", messages[1].getValue("role").jsonPrimitive.content)
            assertEquals("Anchor answer", messages[1].getValue("contentSnippet").jsonPrimitive.content)
            assertEquals(true.toString(), messages[1].getValue("hasProviderMeta").jsonPrimitive.content)
            assertEquals("tool-call-2", messages[2].getValue("toolCallId").jsonPrimitive.content)
            assertFalse(missing.success)
            assertEquals("MISSING_MESSAGE", missing.errorCode)
        }

    @Test
    fun `messages reference lists messages by tool call or task run id`() =
        runTest {
            val session = sessionRepository.createSession("Reference transcript")
            val archivedSession = sessionRepository.createSession("Archived reference transcript")
            val toolCall =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.ToolCall,
                    content = "Tool call body",
                    toolCallId = "tool-call-ref",
                )
            val toolResult =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.ToolResult,
                    content = "Tool result body",
                    toolCallId = "tool-call-ref",
                )
            val archivedTaskMessage =
                messageRepository.addMessage(
                    sessionId = archivedSession.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Archived task output",
                    taskRunId = "task-run-ref",
                )
            sessionRepository.archiveSession(archivedSession.id)
            val registry = buildRegistry()

            val toolReference =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "messages.reference"),
                    arguments =
                        buildJsonObject {
                            put("toolCallId", "tool-call-ref")
                        },
                )
            val taskReference =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "chat.reference"),
                    arguments =
                        buildJsonObject {
                            put("taskRunId", "task-run-ref")
                        },
                )
            val rejected =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "messages.reference"),
                    arguments =
                        buildJsonObject {
                            put("toolCallId", "tool-call-ref")
                            put("taskRunId", "task-run-ref")
                        },
                )

            assertTrue(toolReference.success)
            assertEquals("toolCallId", toolReference.payload["referenceType"]?.jsonPrimitive?.content)
            assertEquals("tool-call-ref", toolReference.payload["referenceId"]?.jsonPrimitive?.content)
            assertEquals("2", toolReference.payload["resultCount"]?.jsonPrimitive?.content)
            val toolMessages =
                toolReference.payload
                    .getValue("messages")
                    .jsonArray
                    .map { message -> message.jsonObject }
            assertEquals(
                listOf(toolResult.id, toolCall.id),
                toolMessages.map { message -> message.getValue("messageId").jsonPrimitive.content },
            )
            assertEquals(
                "Reference transcript",
                toolMessages
                    .first()
                    .getValue("sessionTitle")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "tool-call-ref",
                toolMessages
                    .first()
                    .getValue("toolCallId")
                    .jsonPrimitive
                    .content,
            )
            assertTrue(taskReference.success)
            assertEquals("taskRunId", taskReference.payload["referenceType"]?.jsonPrimitive?.content)
            val taskMessage =
                taskReference.payload
                    .getValue("messages")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(archivedTaskMessage.id, taskMessage.getValue("messageId").jsonPrimitive.content)
            assertEquals(true.toString(), taskMessage.getValue("sessionArchived").jsonPrimitive.content)
            assertEquals("task-run-ref", taskMessage.getValue("taskRunId").jsonPrimitive.content)
            assertFalse(rejected.success)
            assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
        }

    @Test
    fun `messages role lists recent messages by role for one session`() =
        runTest {
            val session = sessionRepository.createSession("Role transcript")
            val otherSession = sessionRepository.createSession("Other role transcript")
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "First user prompt",
            )
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.Assistant,
                content = "Assistant answer",
            )
            val latestUser =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.User,
                    content = "Second user prompt",
                )
            messageRepository.addMessage(
                sessionId = otherSession.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "Other session prompt",
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "chat.by_role", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("role", "user")
                            put("limit", 1)
                        },
                )
            val invalid =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "messages.role", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("role", "developer")
                        },
                )

            assertTrue(result.success)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("User", result.payload["role"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["returnedCount"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["recentFirst"]?.jsonPrimitive?.content)
            val message =
                result.payload
                    .getValue("messages")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(latestUser.id, message.getValue("messageId").jsonPrimitive.content)
            assertEquals("Second user prompt", message.getValue("contentSnippet").jsonPrimitive.content)
            assertEquals("Role transcript", message.getValue("sessionTitle").jsonPrimitive.content)
            assertFalse(invalid.success)
            assertEquals("INVALID_ARGUMENTS", invalid.errorCode)
        }

    @Test
    fun `messages recent returns recent active session messages`() =
        runTest {
            val session = sessionRepository.createSession("Recent transcript")
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "Older setup",
            )
            val recentMessage =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Latest answer",
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "chat.recent", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("limit", 1)
                        },
                )

            assertTrue(result.success)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["messageCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["returnedCount"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["recentFirst"]?.jsonPrimitive?.content)
            val message =
                result.payload
                    .getValue("messages")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(recentMessage.id, message.getValue("messageId").jsonPrimitive.content)
            assertEquals("Assistant", message.getValue("role").jsonPrimitive.content)
            assertEquals("Latest answer", message.getValue("contentSnippet").jsonPrimitive.content)
        }

    @Test
    fun `messages stats returns aggregate transcript counts`() =
        runTest {
            val session = sessionRepository.createSession("Stats transcript")
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.User,
                content = "hello",
            )
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.Assistant,
                content = "world",
            )
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.Assistant,
                content = "again",
            )
            messageRepository.addMessage(
                sessionId = session.id,
                role = ai.androidclaw.data.model.MessageRole.ToolResult,
                content = "ok",
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "chat.stats", sessionId = session.id),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("Stats transcript", result.payload["sessionTitle"]?.jsonPrimitive?.content)
            assertEquals("4", result.payload["messageCount"]?.jsonPrimitive?.content)
            assertEquals("17", result.payload["contentCharCount"]?.jsonPrimitive?.content)
            assertNotNull(result.payload["oldestMessageAtIso"])
            assertNotNull(result.payload["newestMessageAtIso"])
            val roleStats =
                result.payload
                    .getValue("roleStats")
                    .jsonArray
                    .associate { item ->
                        val payload = item.jsonObject
                        payload.getValue("role").jsonPrimitive.content to
                            payload.getValue("messageCount").jsonPrimitive.content
                    }
            assertEquals("1", roleStats.getValue("User"))
            assertEquals("2", roleStats.getValue("Assistant"))
            assertEquals("1", roleStats.getValue("ToolResult"))
        }

    @Test
    fun `sessions compact stores explicit summary for active session`() =
        runTest {
            val session = sessionRepository.getOrCreateMainSession()
            val boundary =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Older messages are covered by this explicit compact summary.",
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.compact", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("command", "Goal: finish /compact. Next: validate and push.")
                            put("compactedUntilMessageId", boundary.id)
                        },
                )

            assertTrue(result.success)
            assertEquals("Compacted this session summary and hid older messages.", result.summary)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("Goal: finish /compact. Next: validate and push.", result.payload["summaryText"]?.jsonPrimitive?.content)
            val storedSession = sessionRepository.getSession(session.id)
            assertEquals("Goal: finish /compact. Next: validate and push.", storedSession?.summaryText)
            assertEquals(boundary.id, storedSession?.compactedUntilMessageId)
        }

    @Test
    fun `sessions compact requires explicit summary text`() =
        runTest {
            val session = sessionRepository.getOrCreateMainSession()
            val boundary =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Boundary message for missing summary validation.",
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.compact", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("command", "   ")
                            put("compactedUntilMessageId", boundary.id)
                        },
                )

            assertFalse(result.success)
            assertEquals("MISSING_SUMMARY", result.errorCode)
            assertEquals(null, sessionRepository.getSession(session.id)?.summaryText)
        }

    @Test
    fun `sessions compact requires a compaction boundary`() =
        runTest {
            val session = sessionRepository.getOrCreateMainSession()
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.compact", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("command", "Summary text without a source boundary.")
                        },
                )

            assertFalse(result.success)
            assertEquals("EMPTY_COMPACT_SOURCE", result.errorCode)
            assertEquals(null, sessionRepository.getSession(session.id)?.summaryText)
        }

    @Test
    fun `sessions compact rejects a missing active session`() =
        runTest {
            val existingSession = sessionRepository.getOrCreateMainSession()
            val boundary =
                messageRepository.addMessage(
                    sessionId = existingSession.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Boundary from an existing session.",
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.compact", sessionId = "missing-session"),
                    arguments =
                        buildJsonObject {
                            put("command", "Summary text for a missing session.")
                            put("compactedUntilMessageId", boundary.id)
                        },
                )

            assertFalse(result.success)
            assertEquals("MISSING_SESSION", result.errorCode)
            assertEquals("missing-session", result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals(null, sessionRepository.getSession(existingSession.id)?.summaryText)
        }

    @Test
    fun `sessions compact rejects a boundary outside the active session`() =
        runTest {
            val session = sessionRepository.getOrCreateMainSession()
            val otherSession = sessionRepository.createSession("Other session")
            val otherBoundary =
                messageRepository.addMessage(
                    sessionId = otherSession.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "This message belongs to another session.",
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.compact", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("command", "Summary text with a cross-session boundary.")
                            put("compactedUntilMessageId", otherBoundary.id)
                        },
                )

            assertFalse(result.success)
            assertEquals("INVALID_COMPACT_BOUNDARY", result.errorCode)
            assertEquals(null, sessionRepository.getSession(session.id)?.summaryText)
            assertEquals(null, sessionRepository.getSession(session.id)?.compactedUntilMessageId)
        }

    @Test
    fun `sessions uncompact clears boundary while preserving summary by default`() =
        runTest {
            val session = sessionRepository.createSession("Expand transcript")
            val boundary =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Boundary answer",
                )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = session.id,
                summaryText = "Keep this summary.",
                compactedUntilMessageId = boundary.id,
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "session.expand", sessionId = session.id),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), result.payload["clearSummary"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), result.payload["previousCompacted"]?.jsonPrimitive?.content)
            assertEquals(boundary.id, result.payload["previousCompactedUntilMessageId"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), result.payload["compacted"]?.jsonPrimitive?.content)
            assertEquals("18", result.payload["summaryLength"]?.jsonPrimitive?.content)
            val stored = sessionRepository.getSession(session.id)
            assertEquals("Keep this summary.", stored?.summaryText)
            assertEquals(null, stored?.compactedUntilMessageId)
        }

    @Test
    fun `sessions uncompact clear summary requires confirmation`() =
        runTest {
            val session = sessionRepository.createSession("Clear compact summary")
            val boundary =
                messageRepository.addMessage(
                    sessionId = session.id,
                    role = ai.androidclaw.data.model.MessageRole.Assistant,
                    content = "Boundary answer",
                )
            sessionRepository.updateSummaryAndCompactionBoundary(
                id = session.id,
                summaryText = "Remove this summary.",
                compactedUntilMessageId = boundary.id,
            )
            val registry = buildRegistry()

            val rejected =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.uncompact", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("clearSummary", true)
                        },
                )

            assertFalse(rejected.success)
            assertEquals("CONFIRMATION_REQUIRED", rejected.errorCode)
            assertEquals("Remove this summary.", sessionRepository.getSession(session.id)?.summaryText)

            val cleared =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.decompact"),
                    arguments =
                        buildJsonObject {
                            put("sessionId", session.id)
                            put("clearSummary", true)
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(cleared.success)
            assertEquals(true.toString(), cleared.payload["clearSummary"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), cleared.payload["compacted"]?.jsonPrimitive?.content)
            assertEquals("0", cleared.payload["summaryLength"]?.jsonPrimitive?.content)
            val stored = sessionRepository.getSession(session.id)
            assertEquals(null, stored?.summaryText)
            assertEquals(null, stored?.compactedUntilMessageId)
        }

    @Test
    fun `tasks list returns canonical task payloads and latest run summary`() =
        runTest {
            val task =
                taskRepository.createTask(
                    name = "Daily check",
                    prompt = "Check health",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val run = taskRepository.recordRun(task.id)
            taskRepository.updateRun(
                run.copy(
                    status = TaskRunStatus.Success,
                    startedAt = run.scheduledAt,
                    finishedAt = run.scheduledAt.plusSeconds(1),
                    resultSummary = "Completed",
                ),
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "task.list"),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals("1", result.payload["taskCount"]?.jsonPrimitive?.content)
            val taskPayload =
                result.payload["tasks"]
                    ?.jsonArray
                    ?.single()
                    ?.jsonObject ?: error("Missing task payload.")
            assertEquals(task.id, taskPayload.getValue("id").jsonPrimitive.content)
            assertEquals("once", taskPayload.getValue("scheduleKind").jsonPrimitive.content)
            assertEquals(
                "Completed",
                taskPayload
                    .getValue("lastRun")
                    .jsonObject
                    .getValue("resultSummary")
                    .jsonPrimitive.content,
            )
            assertEquals("WorkManagerApproximate", taskPayload.getValue("effectiveSchedulingPath").jsonPrimitive.content)
        }

    @Test
    fun `tasks search returns name and prompt matches`() =
        runTest {
            val named =
                taskRepository.createTask(
                    name = "Project Alpha",
                    prompt = "Daily status",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val prompted =
                taskRepository.createTask(
                    name = "Morning review",
                    prompt = "Summarize Alpha milestones",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-11T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            taskRepository.createTask(
                name = "Beta task",
                prompt = "No match",
                schedule = TaskSchedule.Once(Instant.parse("2026-03-12T00:00:00Z")),
                executionMode = TaskExecutionMode.MainSession,
                targetSessionId = null,
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "task.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "Alpha")
                            put("limit", 10)
                        },
                )

            assertTrue(result.success)
            assertEquals("2", result.payload["resultCount"]?.jsonPrimitive?.content)
            val taskIds =
                result.payload
                    .getValue("tasks")
                    .jsonArray
                    .map { item ->
                        item.jsonObject
                            .getValue("id")
                            .jsonPrimitive
                            .content
                    }
            assertEquals(listOf(named.id, prompted.id).sorted(), taskIds.sorted())
        }

    @Test
    fun `tasks stats returns aggregate scheduler and run state`() =
        runTest {
            val dueTask =
                taskRepository.createTask(
                    name = "Due task",
                    prompt = "Run before now",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-07T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val disabledTask =
                taskRepository.createTask(
                    name = "Disabled task",
                    prompt = "Stay disabled",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-06T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            taskRepository.updateTask(disabledTask.copy(enabled = false))
            val futureTask =
                taskRepository.createTask(
                    name = "Future isolated task",
                    prompt = "Run later",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.IsolatedSession,
                    targetSessionId = null,
                )
            val successRun = taskRepository.recordRun(dueTask.id, scheduledAt = Instant.parse("2026-03-07T00:00:00Z"))
            val failureRun = taskRepository.recordRun(futureTask.id, scheduledAt = Instant.parse("2026-03-10T00:00:00Z"))
            taskRepository.updateRun(successRun.copy(status = TaskRunStatus.Success))
            taskRepository.updateRun(failureRun.copy(status = TaskRunStatus.Failure, errorCode = "TEST_FAILURE"))
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automation.stats"),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals("3", result.payload["taskCount"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["enabledTaskCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["disabledTaskCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["dueTaskCount"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["runCount"]?.jsonPrimitive?.content)
            val scheduleStats =
                result.payload
                    .getValue("scheduleKindStats")
                    .jsonArray
                    .associate { item ->
                        val payload = item.jsonObject
                        payload.getValue("scheduleKind").jsonPrimitive.content to
                            payload.getValue("taskCount").jsonPrimitive.content
                    }
            val executionStats =
                result.payload
                    .getValue("executionModeStats")
                    .jsonArray
                    .associate { item ->
                        val payload = item.jsonObject
                        payload.getValue("executionMode").jsonPrimitive.content to
                            payload.getValue("taskCount").jsonPrimitive.content
                    }
            val runStats =
                result.payload
                    .getValue("runStatusStats")
                    .jsonArray
                    .associate { item ->
                        val payload = item.jsonObject
                        payload.getValue("status").jsonPrimitive.content to
                            payload.getValue("runCount").jsonPrimitive.content
                    }
            assertEquals("3", scheduleStats.getValue("once"))
            assertEquals("2", executionStats.getValue("MainSession"))
            assertEquals("1", executionStats.getValue("IsolatedSession"))
            assertEquals("1", runStats.getValue("Success"))
            assertEquals("1", runStats.getValue("Failure"))
        }

    @Test
    fun `tasks preview returns next run without persisting automation`() =
        runTest {
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automation.preview"),
                    arguments =
                        buildJsonObject {
                            put("scheduleKind", "interval")
                            put("anchorAtIso", "2026-03-07T00:00:00Z")
                            put("repeatEveryMinutes", 1_440)
                        },
                )
            val invalid =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "task.schedule.preview"),
                    arguments =
                        buildJsonObject {
                            put("scheduleKind", "once")
                            put("atIso", "2026-03-07T00:00:00Z")
                        },
                )

            assertTrue(result.summary, result.success)
            assertEquals("interval", result.payload["scheduleKind"]?.jsonPrimitive?.content)
            assertEquals("2026-03-09T00:00:00Z", result.payload["nextRunAtIso"]?.jsonPrimitive?.content)
            assertEquals("86400", result.payload["secondsUntilRun"]?.jsonPrimitive?.content)
            val schedule = result.payload.getValue("schedule").jsonObject
            assertEquals("interval", schedule.getValue("kind").jsonPrimitive.content)
            assertEquals("1440", schedule.getValue("repeatEveryMinutes").jsonPrimitive.content)
            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), taskRepository.observeTasks().first())
            assertFalse(invalid.success)
            assertEquals("INVALID_ARGUMENTS", invalid.errorCode)
        }

    @Test
    fun `tasks next returns upcoming enabled automations in next run order`() =
        runTest {
            val dueTask =
                taskRepository.createTask(
                    name = "Due automation",
                    prompt = "Run before now",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-07T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val laterTask =
                taskRepository.createTask(
                    name = "Later automation",
                    prompt = "Run later",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.IsolatedSession,
                    targetSessionId = null,
                )
            val earliestFutureTask =
                taskRepository.createTask(
                    name = "Earlier future automation",
                    prompt = "Run soon",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-09T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val disabledTask =
                taskRepository.createTask(
                    name = "Disabled automation",
                    prompt = "Do not include",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-06T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            taskRepository.updateTask(disabledTask.copy(enabled = false))
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automation.next"),
                    arguments =
                        buildJsonObject {
                            put("limit", 2)
                        },
                )

            assertTrue(result.summary, result.success)
            assertEquals("2", result.payload["returnedCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["dueTaskCount"]?.jsonPrimitive?.content)
            assertEquals("2026-03-07T00:00:00Z", result.payload["soonestRunAtIso"]?.jsonPrimitive?.content)
            val tasks =
                result.payload
                    .getValue("tasks")
                    .jsonArray
                    .map { task -> task.jsonObject }
            assertEquals(
                listOf(dueTask.id, earliestFutureTask.id),
                tasks.map { task -> task.getValue("id").jsonPrimitive.content },
            )
            assertEquals("true", tasks[0].getValue("due").jsonPrimitive.content)
            assertEquals("false", tasks[1].getValue("due").jsonPrimitive.content)
            assertTrue(
                tasks.none { task ->
                    task.getValue("id").jsonPrimitive.content == disabledTask.id ||
                        task.getValue("id").jsonPrimitive.content == laterTask.id
                },
            )
        }

    @Test
    fun `tasks due returns due enabled automations in scheduled order`() =
        runTest {
            val olderDueTask =
                taskRepository.createTask(
                    name = "Older due automation",
                    prompt = "Run first",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-06T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val newerDueTask =
                taskRepository.createTask(
                    name = "Newer due automation",
                    prompt = "Run second",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-07T00:00:00Z")),
                    executionMode = TaskExecutionMode.IsolatedSession,
                    targetSessionId = null,
                )
            val futureTask =
                taskRepository.createTask(
                    name = "Future automation",
                    prompt = "Run later",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-09T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val disabledTask =
                taskRepository.createTask(
                    name = "Disabled due automation",
                    prompt = "Do not include",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-05T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            taskRepository.updateTask(disabledTask.copy(enabled = false))
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automation.due"),
                    arguments =
                        buildJsonObject {
                            put("limit", 2)
                        },
                )

            assertTrue(result.summary, result.success)
            assertEquals("2", result.payload["returnedCount"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["dueTaskCount"]?.jsonPrimitive?.content)
            assertEquals("2026-03-06T00:00:00Z", result.payload["oldestDueAtIso"]?.jsonPrimitive?.content)
            assertEquals("2026-03-07T00:00:00Z", result.payload["newestDueAtIso"]?.jsonPrimitive?.content)
            val tasks =
                result.payload
                    .getValue("tasks")
                    .jsonArray
                    .map { task -> task.jsonObject }
            assertEquals(
                listOf(olderDueTask.id, newerDueTask.id),
                tasks.map { task -> task.getValue("id").jsonPrimitive.content },
            )
            assertEquals("true", tasks[0].getValue("due").jsonPrimitive.content)
            assertEquals("172800", tasks[0].getValue("secondsOverdue").jsonPrimitive.content)
            assertTrue(
                tasks.none { task ->
                    task.getValue("id").jsonPrimitive.content == disabledTask.id ||
                        task.getValue("id").jsonPrimitive.content == futureTask.id
                },
            )
        }

    @Test
    fun `tasks skip records skipped run and clears due once automation`() =
        runTest {
            val task =
                taskRepository.createTask(
                    name = "Due one-shot automation",
                    prompt = "Skip this prompt",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-07T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val futureTask =
                taskRepository.createTask(
                    name = "Future one-shot automation",
                    prompt = "Not due yet",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-09T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automation.skip"),
                    arguments =
                        buildJsonObject {
                            put("taskId", task.id)
                        },
                )
            val notDueResult =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "task.skip"),
                    arguments =
                        buildJsonObject {
                            put("taskId", futureTask.id)
                        },
                )

            assertTrue(result.summary, result.success)
            assertEquals("2026-03-08T00:00:00Z", result.payload["skippedAtIso"]?.jsonPrimitive?.content)
            assertEquals("2026-03-07T00:00:00Z", result.payload["previousNextRunAtIso"]?.jsonPrimitive?.content)
            val run = result.payload.getValue("run").jsonObject
            assertEquals("Skipped", run.getValue("status").jsonPrimitive.content)
            assertEquals("2026-03-07T00:00:00Z", run.getValue("scheduledAtIso").jsonPrimitive.content)
            assertEquals("Skipped by tasks.skip.", run.getValue("resultSummary").jsonPrimitive.content)
            val reloadedTask = taskRepository.getTask(task.id)
            assertEquals(null, reloadedTask?.nextRunAt)
            assertEquals(Instant.parse("2026-03-08T00:00:00Z"), reloadedTask?.lastRunAt)
            assertEquals(0, reloadedTask?.failureCount)
            assertFalse(notDueResult.success)
            assertEquals("TASK_NOT_DUE", notDueResult.errorCode)
        }

    @Test
    fun `tasks snooze postpones due automations with delay or target instant`() =
        runTest {
            val delayedTask =
                taskRepository.createTask(
                    name = "Delay snooze automation",
                    prompt = "Run later by delay",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-07T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val untilTask =
                taskRepository.createTask(
                    name = "Instant snooze automation",
                    prompt = "Run later by instant",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-06T00:00:00Z")),
                    executionMode = TaskExecutionMode.IsolatedSession,
                    targetSessionId = null,
                )
            val futureTask =
                taskRepository.createTask(
                    name = "Future snooze automation",
                    prompt = "Not due yet",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-09T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val registry = buildRegistry()

            val delayResult =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automation.snooze"),
                    arguments =
                        buildJsonObject {
                            put("taskId", delayedTask.id)
                            put("delayMinutes", 30)
                        },
                )
            val untilResult =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "task.postpone"),
                    arguments =
                        buildJsonObject {
                            put("taskId", untilTask.id)
                            put("untilIso", "2026-03-08T02:00:00Z")
                        },
                )
            val notDueResult =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.snooze"),
                    arguments =
                        buildJsonObject {
                            put("taskId", futureTask.id)
                        },
                )

            assertTrue(delayResult.summary, delayResult.success)
            assertEquals("2026-03-08T00:30:00Z", delayResult.payload["snoozedUntilIso"]?.jsonPrimitive?.content)
            assertEquals("1800", delayResult.payload["snoozeDelaySeconds"]?.jsonPrimitive?.content)
            assertEquals(Instant.parse("2026-03-08T00:30:00Z"), taskRepository.getTask(delayedTask.id)?.nextRunAt)
            assertEquals(null, taskRepository.getLatestRun(delayedTask.id))
            assertTrue(untilResult.summary, untilResult.success)
            assertEquals("2026-03-08T02:00:00Z", untilResult.payload["snoozedUntilIso"]?.jsonPrimitive?.content)
            assertEquals(Instant.parse("2026-03-08T02:00:00Z"), taskRepository.getTask(untilTask.id)?.nextRunAt)
            assertFalse(notDueResult.success)
            assertEquals("TASK_NOT_DUE", notDueResult.errorCode)
        }

    @Test
    fun `tasks reschedule recomputes next run and clears retry state`() =
        runTest {
            val intervalTask =
                taskRepository.createTask(
                    name = "Interval automation",
                    prompt = "Run on interval",
                    schedule =
                        TaskSchedule.Interval(
                            anchorAt = Instant.parse("2026-03-07T00:00:00Z"),
                            repeatEvery = Duration.ofDays(1),
                        ),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                    maxRetries = 3,
                )
            taskRepository.updateTask(
                intervalTask.copy(
                    nextRunAt = Instant.parse("2026-03-07T00:00:00Z"),
                    failureCount = 2,
                ),
            )
            val onceTask =
                taskRepository.createTask(
                    name = "Past one-shot automation",
                    prompt = "No future run",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-07T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val registry = buildRegistry()

            val intervalResult =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automation.reschedule"),
                    arguments =
                        buildJsonObject {
                            put("taskId", intervalTask.id)
                        },
                )
            val onceResult =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "task.recompute_next"),
                    arguments =
                        buildJsonObject {
                            put("taskId", onceTask.id)
                        },
                )

            assertTrue(intervalResult.summary, intervalResult.success)
            assertEquals("2026-03-07T00:00:00Z", intervalResult.payload["previousNextRunAtIso"]?.jsonPrimitive?.content)
            assertEquals("2026-03-09T00:00:00Z", intervalResult.payload["nextRunAtIso"]?.jsonPrimitive?.content)
            assertEquals("true", intervalResult.payload["failureCountCleared"]?.jsonPrimitive?.content)
            val reloadedIntervalTask = taskRepository.getTask(intervalTask.id)
            assertEquals(Instant.parse("2026-03-09T00:00:00Z"), reloadedIntervalTask?.nextRunAt)
            assertEquals(0, reloadedIntervalTask?.failureCount)
            assertTrue(onceResult.summary, onceResult.success)
            assertEquals(null, taskRepository.getTask(onceTask.id)?.nextRunAt)
        }

    @Test
    fun `tasks runs returns bounded recent run history`() =
        runTest {
            val task =
                taskRepository.createTask(
                    name = "History task",
                    prompt = "Check run history",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val older = taskRepository.recordRun(task.id, scheduledAt = Instant.parse("2026-03-10T00:00:00Z"))
            val newer = taskRepository.recordRun(task.id, scheduledAt = Instant.parse("2026-03-11T00:00:00Z"))
            taskRepository.updateRun(
                older.copy(
                    status = TaskRunStatus.Failure,
                    errorCode = "OLDER_FAILURE",
                    errorMessage = "Older failure",
                ),
            )
            taskRepository.updateRun(
                newer.copy(
                    status = TaskRunStatus.Success,
                    startedAt = newer.scheduledAt,
                    finishedAt = newer.scheduledAt.plusSeconds(2),
                    resultSummary = "Newest success",
                ),
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "task.history"),
                    arguments =
                        buildJsonObject {
                            put("taskId", task.id)
                            put("limit", 1)
                        },
                )

            assertTrue(result.success)
            assertEquals(task.id, result.payload["taskId"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["returnedCount"]?.jsonPrimitive?.content)
            val runPayload =
                result.payload
                    .getValue("runs")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(newer.id, runPayload.getValue("id").jsonPrimitive.content)
            assertEquals("Success", runPayload.getValue("status").jsonPrimitive.content)
            assertEquals("Newest success", runPayload.getValue("resultSummary").jsonPrimitive.content)
        }

    @Test
    fun `tasks run get returns one run by id`() =
        runTest {
            val task =
                taskRepository.createTask(
                    name = "Exact run task",
                    prompt = "Inspect a run",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val run = taskRepository.recordRun(task.id, scheduledAt = Instant.parse("2026-03-10T00:00:00Z"))
            taskRepository.updateRun(
                run.copy(
                    status = TaskRunStatus.Failure,
                    errorCode = "RUN_FAILED",
                    errorMessage = "Failure summary",
                ),
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "taskrun.get"),
                    arguments =
                        buildJsonObject {
                            put("runId", run.id)
                        },
                )

            assertTrue(result.success)
            assertEquals(task.id, result.payload["taskId"]?.jsonPrimitive?.content)
            assertEquals("Exact run task", result.payload["taskName"]?.jsonPrimitive?.content)
            val runPayload = result.payload.getValue("run").jsonObject
            assertEquals(run.id, runPayload.getValue("id").jsonPrimitive.content)
            assertEquals("Failure", runPayload.getValue("status").jsonPrimitive.content)
            assertEquals("RUN_FAILED", runPayload.getValue("errorCode").jsonPrimitive.content)
        }

    @Test
    fun `tasks run retry queues manual execution for failed run`() =
        runTest {
            val task =
                taskRepository.createTask(
                    name = "Retryable automation",
                    prompt = "Retry the failed work.",
                    schedule =
                        TaskSchedule.Interval(
                            anchorAt = Instant.parse("2026-03-08T00:00:00Z"),
                            repeatEvery = java.time.Duration.ofMinutes(30),
                        ),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            schedulerCoordinator.scheduleTask(task.id)
            val initialNextRun = taskRepository.getTask(task.id)?.nextRunAt
            val failedRun =
                taskRepository.recordRun(
                    taskId = task.id,
                    scheduledAt = Instant.parse("2026-03-07T23:30:00Z"),
                )
            taskRepository.updateRun(
                failedRun.copy(
                    status = TaskRunStatus.Failure,
                    errorCode = "PROVIDER_OFFLINE",
                    errorMessage = "Network unavailable",
                ),
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automation.run.retry"),
                    arguments =
                        buildJsonObject {
                            put("runId", failedRun.id)
                        },
                )

            assertTrue(result.summary, result.success)
            assertEquals(failedRun.id, result.payload["retryOfRunId"]?.jsonPrimitive?.content)
            assertEquals("2026-03-08T00:00:00Z", result.payload["queuedAtIso"]?.jsonPrimitive?.content)
            assertEquals("manual_retry", result.payload["trigger"]?.jsonPrimitive?.content)
            assertEquals(
                "Failure",
                result.payload
                    .getValue("sourceRun")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )
            val payloadTask = result.payload.getValue("task").jsonObject
            assertEquals(task.id, payloadTask.getValue("id").jsonPrimitive.content)
            assertEquals(initialNextRun, taskRepository.getTask(task.id)?.nextRunAt)
            val retryWorkInfos =
                WorkManager
                    .getInstance(application)
                    .getWorkInfosForUniqueWork(SchedulerCoordinator.runNowWorkName(task.id))
                    .get()
            assertEquals(1, retryWorkInfos.size)
            assertTrue(retryWorkInfos.single().state != WorkInfo.State.CANCELLED)
        }

    @Test
    fun `tasks run retry rejects successful run`() =
        runTest {
            val task =
                taskRepository.createTask(
                    name = "Successful automation",
                    prompt = "Do not retry success.",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val run = taskRepository.recordRun(task.id, scheduledAt = Instant.parse("2026-03-10T00:00:00Z"))
            taskRepository.updateRun(run.copy(status = TaskRunStatus.Success, resultSummary = "Already done"))
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.run.retry"),
                    arguments =
                        buildJsonObject {
                            put("runId", run.id)
                        },
                )

            assertFalse(result.success)
            assertEquals("TASK_RUN_NOT_RETRYABLE", result.errorCode)
            assertEquals("Success", result.payload["status"]?.jsonPrimitive?.content)
        }

    @Test
    fun `tasks runs recent returns recent automation runs across tasks`() =
        runTest {
            val firstTask =
                taskRepository.createTask(
                    name = "First recent automation",
                    prompt = "Run first",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-07T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val secondTask =
                taskRepository.createTask(
                    name = "Second recent automation",
                    prompt = "Run second",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-08T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val older = taskRepository.recordRun(firstTask.id, scheduledAt = Instant.parse("2026-03-07T00:00:00Z"))
            val middle = taskRepository.recordRun(firstTask.id, scheduledAt = Instant.parse("2026-03-08T00:00:00Z"))
            val newer = taskRepository.recordRun(secondTask.id, scheduledAt = Instant.parse("2026-03-09T00:00:00Z"))
            taskRepository.updateRun(older.copy(status = TaskRunStatus.Failure, errorCode = "OLDER_FAILURE"))
            taskRepository.updateRun(middle.copy(status = TaskRunStatus.Success, resultSummary = "Middle success"))
            taskRepository.updateRun(newer.copy(status = TaskRunStatus.Running))
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automation.runs.recent"),
                    arguments =
                        buildJsonObject {
                            put("limit", 2)
                        },
                )

            assertTrue(result.summary, result.success)
            assertEquals("2", result.payload["returnedCount"]?.jsonPrimitive?.content)
            val runs =
                result.payload
                    .getValue("runs")
                    .jsonArray
                    .map { item -> item.jsonObject }
            assertEquals(
                listOf(newer.id, middle.id),
                runs.map { item ->
                    item
                        .getValue("run")
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content
                },
            )
            assertEquals(secondTask.id, runs[0].getValue("taskId").jsonPrimitive.content)
            assertEquals("Second recent automation", runs[0].getValue("taskName").jsonPrimitive.content)
            assertEquals(
                "Running",
                runs[0]
                    .getValue("run")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )
        }

    @Test
    fun `tasks runs status returns recent automation runs for requested status`() =
        runTest {
            val firstTask =
                taskRepository.createTask(
                    name = "First skipped automation",
                    prompt = "Skip first",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-07T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val secondTask =
                taskRepository.createTask(
                    name = "Second skipped automation",
                    prompt = "Skip second",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-08T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val olderSkipped =
                taskRepository.recordRun(
                    taskId = firstTask.id,
                    scheduledAt = Instant.parse("2026-03-07T00:00:00Z"),
                )
            val success =
                taskRepository.recordRun(
                    taskId = firstTask.id,
                    scheduledAt = Instant.parse("2026-03-08T00:00:00Z"),
                )
            val newerSkipped =
                taskRepository.recordRun(
                    taskId = secondTask.id,
                    scheduledAt = Instant.parse("2026-03-09T00:00:00Z"),
                )
            taskRepository.updateRun(olderSkipped.copy(status = TaskRunStatus.Skipped))
            taskRepository.updateRun(success.copy(status = TaskRunStatus.Success))
            taskRepository.updateRun(newerSkipped.copy(status = TaskRunStatus.Skipped))
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automation.runs.status"),
                    arguments =
                        buildJsonObject {
                            put("status", "skipped")
                            put("limit", 2)
                        },
                )

            assertTrue(result.summary, result.success)
            assertEquals("Skipped", result.payload["status"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["returnedCount"]?.jsonPrimitive?.content)
            val runs =
                result.payload
                    .getValue("runs")
                    .jsonArray
                    .map { item -> item.jsonObject }
            assertEquals(
                listOf(newerSkipped.id, olderSkipped.id),
                runs.map { item ->
                    item
                        .getValue("run")
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content
                },
            )
            assertEquals(secondTask.id, runs[0].getValue("taskId").jsonPrimitive.content)
            assertEquals("Second skipped automation", runs[0].getValue("taskName").jsonPrimitive.content)
            assertEquals(
                "Skipped",
                runs[0]
                    .getValue("run")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )

            val invalid =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.runs.status"),
                    arguments =
                        buildJsonObject {
                            put("status", "unknown")
                        },
                )
            assertFalse(invalid.success)
            assertEquals("INVALID_ARGUMENTS", invalid.errorCode)
        }

    @Test
    fun `tasks failures returns recent failed automation runs with task metadata`() =
        runTest {
            val firstTask =
                taskRepository.createTask(
                    name = "First failing automation",
                    prompt = "Fail first",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-07T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val secondTask =
                taskRepository.createTask(
                    name = "Second failing automation",
                    prompt = "Fail second",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-08T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val olderFailure = taskRepository.recordRun(firstTask.id, scheduledAt = Instant.parse("2026-03-07T00:00:00Z"))
            val success = taskRepository.recordRun(firstTask.id, scheduledAt = Instant.parse("2026-03-08T00:00:00Z"))
            val newerFailure = taskRepository.recordRun(secondTask.id, scheduledAt = Instant.parse("2026-03-09T00:00:00Z"))
            taskRepository.updateRun(olderFailure.copy(status = TaskRunStatus.Failure, errorCode = "OLDER_FAILURE"))
            taskRepository.updateRun(success.copy(status = TaskRunStatus.Success))
            taskRepository.updateRun(newerFailure.copy(status = TaskRunStatus.Failure, errorCode = "NEWER_FAILURE"))
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automation.failures"),
                    arguments =
                        buildJsonObject {
                            put("limit", 2)
                        },
                )

            assertTrue(result.summary, result.success)
            assertEquals("2", result.payload["returnedCount"]?.jsonPrimitive?.content)
            val failures =
                result.payload
                    .getValue("runs")
                    .jsonArray
                    .map { item -> item.jsonObject }
            assertEquals(
                listOf(newerFailure.id, olderFailure.id),
                failures.map { item ->
                    item
                        .getValue("run")
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content
                },
            )
            assertEquals(secondTask.id, failures[0].getValue("taskId").jsonPrimitive.content)
            assertEquals("Second failing automation", failures[0].getValue("taskName").jsonPrimitive.content)
            assertEquals("true", failures[0].getValue("taskAvailable").jsonPrimitive.content)
        }

    @Test
    fun `tasks duplicate creates a disabled copy by default`() =
        runTest {
            val targetSession = sessionRepository.createSession("Automation target")
            val source =
                taskRepository.createTask(
                    name = "Daily briefing",
                    prompt = "Summarize today's work.",
                    schedule =
                        TaskSchedule.Interval(
                            anchorAt = Instant.parse("2026-03-08T09:00:00Z"),
                            repeatEvery = java.time.Duration.ofHours(24),
                        ),
                    executionMode = TaskExecutionMode.IsolatedSession,
                    targetSessionId = targetSession.id,
                    precise = true,
                    maxRetries = 5,
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.copy"),
                    arguments =
                        buildJsonObject {
                            put("taskId", source.id)
                        },
                )

            assertTrue(result.summary, result.success)
            assertEquals(source.id, result.payload["sourceTaskId"]?.jsonPrimitive?.content)
            val copied = taskRepository.observeTasks().first().single { task -> task.id != source.id }
            assertEquals("Copy of Daily briefing", copied.name)
            assertEquals(source.prompt, copied.prompt)
            assertEquals(source.schedule, copied.schedule)
            assertEquals(TaskExecutionMode.IsolatedSession, copied.executionMode)
            assertEquals(targetSession.id, copied.targetSessionId)
            assertEquals(source.precise, copied.precise)
            assertEquals(5, copied.maxRetries)
            assertFalse(copied.enabled)
            val payloadTask = result.payload.getValue("task").jsonObject
            assertEquals(copied.id, payloadTask.getValue("id").jsonPrimitive.content)
            assertEquals("false", payloadTask.getValue("enabled").jsonPrimitive.content)
        }

    @Test
    fun `tasks duplicate can enable and schedule the copy`() =
        runTest {
            val source =
                taskRepository.createTask(
                    name = "Enabled template",
                    prompt = "Run the enabled copy.",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-20T08:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.duplicate"),
                    arguments =
                        buildJsonObject {
                            put("taskId", source.id)
                            put("name", "Enabled copy")
                            put("enabled", true)
                        },
                )

            assertTrue(result.summary, result.success)
            val copied = taskRepository.observeTasks().first().single { task -> task.id != source.id }
            assertEquals("Enabled copy", copied.name)
            assertTrue(copied.enabled)
            val workInfos =
                WorkManager
                    .getInstance(application)
                    .getWorkInfosForUniqueWork(SchedulerCoordinator.nextWorkName(copied.id))
                    .get()
            assertEquals(1, workInfos.size)
            assertEquals(WorkInfo.State.ENQUEUED, workInfos.single().state)
        }

    @Test
    fun `tasks create resolves current session alias and schedules work`() =
        runTest {
            val currentSession = sessionRepository.createSession("Current session")
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context =
                        ToolExecutionContext(
                            sessionId = currentSession.id,
                            taskRunId = null,
                            origin = ToolInvocationOrigin.Model,
                            runMode = ai.androidclaw.runtime.providers.ModelRunMode.Interactive,
                            requestedName = "tasks.create",
                            canonicalName = "tasks.create",
                            requestId = "req-create",
                            activeSkillId = null,
                        ),
                    arguments =
                        buildJsonObject {
                            put("name", "Morning summary")
                            put("prompt", "Summarize my tasks")
                            put("scheduleKind", "once")
                            put("atIso", "2026-03-20T08:00:00Z")
                            put("targetSessionAlias", "current")
                        },
                )

            assertTrue(result.success)
            val createdTask = taskRepository.observeTasks().first().single()
            assertEquals("Morning summary", createdTask.name)
            assertEquals(currentSession.id, createdTask.targetSessionId)
            assertNotNull(createdTask.nextRunAt)
            val payload = result.payload.getValue("task").jsonObject
            assertEquals(currentSession.id, payload.getValue("targetSessionId").jsonPrimitive.content)
            assertEquals(
                currentSession.id,
                payload
                    .getValue("resolvedTargetSession")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
            val workInfos =
                WorkManager
                    .getInstance(application)
                    .getWorkInfosForUniqueWork(SchedulerCoordinator.nextWorkName(createdTask.id))
                    .get()
            assertEquals(1, workInfos.size)
            assertEquals(WorkInfo.State.ENQUEUED, workInfos.single().state)
        }

    @Test
    fun `tasks create rejects current session alias when context session is stale`() =
        runTest {
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.create", sessionId = "missing-session"),
                    arguments =
                        buildJsonObject {
                            put("name", "Stale current session")
                            put("prompt", "This should fail")
                            put("scheduleKind", "once")
                            put("atIso", "2026-03-20T08:00:00Z")
                            put("targetSessionAlias", "current")
                        },
                )

            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertTrue(result.summary.contains("Current session missing-session was not found"))
            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), taskRepository.observeTasks().first())
        }

    @Test
    fun `tasks create rejects archived explicit target session`() =
        runTest {
            val archivedSession = sessionRepository.createSession("Archived target")
            sessionRepository.archiveSession(archivedSession.id)
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.create"),
                    arguments =
                        buildJsonObject {
                            put("name", "Archived target task")
                            put("prompt", "This should fail")
                            put("scheduleKind", "once")
                            put("atIso", "2026-03-20T08:00:00Z")
                            put("targetSessionId", archivedSession.id)
                        },
                )

            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertTrue(result.summary.contains("is archived"))
            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), taskRepository.observeTasks().first())
        }

    @Test
    fun `tasks create rejects current session alias when context session is archived`() =
        runTest {
            val archivedSession = sessionRepository.createSession("Archived current")
            sessionRepository.archiveSession(archivedSession.id)
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.create", sessionId = archivedSession.id),
                    arguments =
                        buildJsonObject {
                            put("name", "Archived current task")
                            put("prompt", "This should fail")
                            put("scheduleKind", "once")
                            put("atIso", "2026-03-20T08:00:00Z")
                            put("targetSessionAlias", "current")
                        },
                )

            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertTrue(result.summary.contains("is archived"))
            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), taskRepository.observeTasks().first())
        }

    @Test
    fun `tasks create ignores blank optional target session fields from model output`() =
        runTest {
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.create"),
                    arguments =
                        buildJsonObject {
                            put("name", "Model generated reminder")
                            put("prompt", "Check status")
                            put("scheduleKind", "interval")
                            put("anchorAtIso", "2026-03-20T08:00:00Z")
                            put("repeatEveryMinutes", 30)
                            put("targetSessionId", "")
                            put("targetSessionAlias", "")
                        },
                )

            assertTrue(result.success)
            val createdTask = taskRepository.observeTasks().first().single()
            val mainSession = sessionRepository.getOrCreateMainSession()
            assertEquals(mainSession.id, createdTask.targetSessionId)
        }

    @Test
    fun `tasks create rejects once schedules in the past`() =
        runTest {
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.create"),
                    arguments =
                        buildJsonObject {
                            put("name", "Past reminder")
                            put("prompt", "This should fail")
                            put("scheduleKind", "once")
                            put("atIso", "2026-03-07T23:59:00Z")
                        },
                )

            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertTrue(result.summary.contains("future"))
        }

    @Test
    fun `tasks create rejects overflowing retry counts`() =
        runTest {
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.create"),
                    arguments =
                        buildJsonObject {
                            put("name", "Overflowing retry budget")
                            put("prompt", "This should fail")
                            put("scheduleKind", "once")
                            put("atIso", "2026-03-20T08:00:00Z")
                            put("maxRetries", 4_294_967_296L)
                        },
                )

            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertTrue(result.summary.contains("maxRetries"))
            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), taskRepository.observeTasks().first())
        }

    @Test
    fun `tasks create rejects interval cadence values that cannot be safely represented`() =
        runTest {
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.create"),
                    arguments =
                        buildJsonObject {
                            put("name", "Unsafe cadence")
                            put("prompt", "This should fail")
                            put("scheduleKind", "interval")
                            put("anchorAtIso", "2026-03-20T08:00:00Z")
                            put("repeatEveryMinutes", Long.MAX_VALUE)
                        },
                )

            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertTrue(result.summary.contains("repeatEveryMinutes"))
            assertEquals(emptyList<ai.androidclaw.data.model.Task>(), taskRepository.observeTasks().first())
        }

    @Test
    fun `tasks update patches the schedule and prompt`() =
        runTest {
            val created =
                taskRepository.createTask(
                    name = "Draft task",
                    prompt = "Old prompt",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.update"),
                    arguments =
                        buildJsonObject {
                            put("taskId", created.id)
                            put("prompt", "New prompt")
                            put("scheduleKind", "interval")
                            put("anchorAtIso", "2026-03-09T00:00:00Z")
                            put("repeatEveryMinutes", 30)
                            put("maxRetries", 5)
                        },
                )

            assertTrue(result.success)
            val updated = taskRepository.getTask(created.id) ?: error("Missing updated task.")
            assertEquals("New prompt", updated.prompt)
            assertEquals(5, updated.maxRetries)
            val schedule = updated.schedule as? TaskSchedule.Interval ?: error("Expected interval schedule.")
            assertEquals(30, schedule.repeatEvery.toMinutes())
            assertNotNull(updated.nextRunAt)
        }

    @Test
    fun `tasks update patches metadata on past once task without revalidating unchanged schedule`() =
        runTest {
            val created =
                taskRepository.createTask(
                    name = "Past once task",
                    prompt = "Old prompt",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-07T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val originalNextRunAt = created.nextRunAt
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.update"),
                    arguments =
                        buildJsonObject {
                            put("taskId", created.id)
                            put("prompt", "New prompt")
                        },
                )

            assertTrue(result.summary, result.success)
            val updated = taskRepository.getTask(created.id) ?: error("Missing updated task.")
            assertEquals("New prompt", updated.prompt)
            assertEquals(created.schedule, updated.schedule)
            assertEquals(originalNextRunAt, updated.nextRunAt)
        }

    @Test
    fun `tasks update rejects current session alias when context session is stale`() =
        runTest {
            val created =
                taskRepository.createTask(
                    name = "Draft task",
                    prompt = "Old prompt",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.update", sessionId = "missing-session"),
                    arguments =
                        buildJsonObject {
                            put("taskId", created.id)
                            put("targetSessionAlias", "current")
                        },
                )

            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertTrue(result.summary.contains("Current session missing-session was not found"))
            assertEquals(null, taskRepository.getTask(created.id)?.targetSessionId)
        }

    @Test
    fun `tasks disable all and enable all require confirmation and toggle persisted automations`() =
        runTest {
            val enabledTask =
                taskRepository.createTask(
                    name = "Enabled task",
                    prompt = "Run enabled",
                    schedule =
                        TaskSchedule.Interval(
                            anchorAt = Instant.parse("2026-03-08T00:00:00Z"),
                            repeatEvery = java.time.Duration.ofMinutes(30),
                        ),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            val disabledTask =
                taskRepository.createTask(
                    name = "Disabled task",
                    prompt = "Run disabled",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            taskRepository.updateTask(
                disabledTask.copy(
                    enabled = false,
                    updatedAt = Instant.parse("2026-03-08T00:00:00Z"),
                ),
            )
            val registry = buildRegistry()

            val denied =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automations.pause_all"),
                    arguments = buildJsonObject {},
                )

            assertFalse(denied.success)
            assertEquals("CONFIRMATION_REQUIRED", denied.errorCode)
            assertEquals(true, taskRepository.getTask(enabledTask.id)?.enabled)
            assertEquals(false, taskRepository.getTask(disabledTask.id)?.enabled)

            val paused =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "automations.pause_all"),
                    arguments =
                        buildJsonObject {
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(paused.success)
            assertEquals("2", paused.payload["taskCount"]?.jsonPrimitive?.content)
            assertEquals("1", paused.payload["updatedTaskCount"]?.jsonPrimitive?.content)
            assertEquals("1", paused.payload["unchangedTaskCount"]?.jsonPrimitive?.content)
            assertEquals("0", paused.payload["updatedTasksOmitted"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), paused.payload["enabled"]?.jsonPrimitive?.content)
            assertEquals(
                enabledTask.id,
                paused.payload
                    .getValue("updatedTasks")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(false, taskRepository.getTask(enabledTask.id)?.enabled)
            assertEquals(false, taskRepository.getTask(disabledTask.id)?.enabled)

            val resumed =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "task.resume_all"),
                    arguments =
                        buildJsonObject {
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(resumed.success)
            assertEquals("2", resumed.payload["updatedTaskCount"]?.jsonPrimitive?.content)
            assertEquals("0", resumed.payload["unchangedTaskCount"]?.jsonPrimitive?.content)
            assertEquals("0", resumed.payload["updatedTasksOmitted"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), resumed.payload["enabled"]?.jsonPrimitive?.content)
            val resumedIds =
                resumed.payload
                    .getValue("updatedTasks")
                    .jsonArray
                    .map { item ->
                        item.jsonObject
                            .getValue("id")
                            .jsonPrimitive
                            .content
                    }.sorted()
            assertEquals(listOf(disabledTask.id, enabledTask.id).sorted(), resumedIds)
            assertEquals(true, taskRepository.getTask(enabledTask.id)?.enabled)
            assertEquals(true, taskRepository.getTask(disabledTask.id)?.enabled)
        }

    @Test
    fun `tasks disable and run_now manage work without changing the future schedule`() =
        runTest {
            val created =
                taskRepository.createTask(
                    name = "Recurring task",
                    prompt = "Check status",
                    schedule =
                        TaskSchedule.Interval(
                            anchorAt = Instant.parse("2026-03-08T00:00:00Z"),
                            repeatEvery = java.time.Duration.ofMinutes(30),
                        ),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            schedulerCoordinator.scheduleTask(created.id)
            val initialNextRun = taskRepository.getTask(created.id)?.nextRunAt
            val registry = buildRegistry()

            val runNowResult =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.run_now"),
                    arguments =
                        buildJsonObject {
                            put("taskId", created.id)
                        },
                )

            assertTrue(runNowResult.success)
            assertEquals(initialNextRun, taskRepository.getTask(created.id)?.nextRunAt)
            val runNowInfos =
                WorkManager
                    .getInstance(application)
                    .getWorkInfosForUniqueWork(SchedulerCoordinator.runNowWorkName(created.id))
                    .get()
            assertEquals(1, runNowInfos.size)
            assertTrue(runNowInfos.single().state != WorkInfo.State.CANCELLED)

            val disableResult =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.disable"),
                    arguments =
                        buildJsonObject {
                            put("taskId", created.id)
                        },
                )

            assertTrue(disableResult.success)
            assertFalse(taskRepository.getTask(created.id)?.enabled ?: true)
        }

    @Test
    fun `tasks delete removes the task and its future work`() =
        runTest {
            val created =
                taskRepository.createTask(
                    name = "Delete me",
                    prompt = "Remove me",
                    schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
                    executionMode = TaskExecutionMode.MainSession,
                    targetSessionId = null,
                )
            schedulerCoordinator.scheduleTask(created.id)
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tasks.delete"),
                    arguments =
                        buildJsonObject {
                            put("taskId", created.id)
                        },
                )

            assertTrue(result.success)
            assertEquals(created.id, result.payload["deletedTaskId"]?.jsonPrimitive?.content)
            assertEquals(null, taskRepository.getTask(created.id))
            val nextWorkInfos =
                WorkManager
                    .getInstance(application)
                    .getWorkInfosForUniqueWork(SchedulerCoordinator.nextWorkName(created.id))
                    .get()
            assertTrue(nextWorkInfos.isEmpty() || nextWorkInfos.all { it.state == WorkInfo.State.CANCELLED })
        }

    @Test
    fun `provider tools expose selected provider and endpoint metadata`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot().copy(providerType = ProviderType.OpenAiCompatible),
            )
            val registry = buildRegistry()

            val listed =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "provider.list"),
                    arguments = buildJsonObject {},
                )
            val current =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "providers.current"),
                    arguments = buildJsonObject {},
                )
            val deepSeek =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "providers.get"),
                    arguments =
                        buildJsonObject {
                            put("providerId", "deepseek")
                        },
                )
            val selectedKimi =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "provider.select"),
                    arguments =
                        buildJsonObject {
                            put("name", "Kimi")
                        },
                )

            assertTrue(listed.success)
            assertEquals(
                "openai-compatible",
                listed.payload
                    .getValue("currentProviderId")
                    .jsonPrimitive.content,
            )
            val selectedProvider =
                listed.payload
                    .getValue("providers")
                    .jsonArray
                    .first { provider ->
                        provider.jsonObject
                            .getValue("providerId")
                            .jsonPrimitive.content == "openai-compatible"
                    }.jsonObject
            assertEquals("true", selectedProvider.getValue("selected").jsonPrimitive.content)
            assertEquals("ApiKey", selectedProvider.getValue("authMode").jsonPrimitive.content)
            assertEquals(
                "https://api.openai.com/v1",
                selectedProvider
                    .getValue("endpointSettings")
                    .jsonObject
                    .getValue("baseUrl")
                    .jsonPrimitive.content,
            )

            assertTrue(current.success)
            assertEquals(
                "openai-compatible",
                current.payload
                    .getValue("provider")
                    .jsonObject
                    .getValue("providerId")
                    .jsonPrimitive.content,
            )
            assertTrue(deepSeek.success)
            assertEquals(
                "deepseek-v4-flash",
                deepSeek.payload
                    .getValue("provider")
                    .jsonObject
                    .getValue("endpointSettings")
                    .jsonObject
                    .getValue("modelId")
                    .jsonPrimitive.content,
            )
            assertTrue(selectedKimi.success)
            assertEquals(ProviderType.Kimi, settingsDataStore.settings.first().providerType)
            assertEquals(
                "kimi",
                selectedKimi.payload
                    .getValue("provider")
                    .jsonObject
                    .getValue("providerId")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "true",
                selectedKimi.payload
                    .getValue("provider")
                    .jsonObject
                    .getValue("selected")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `provider configure updates non-secret endpoint settings`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot().copy(providerType = ProviderType.OpenAiCompatible),
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "provider.configure"),
                    arguments =
                        buildJsonObject {
                            put("providerId", "deepseek")
                            put("baseUrl", "https://proxy.example/v1")
                            put("modelId", "deepseek-test")
                            put("timeoutSeconds", 120)
                        },
                )

            assertTrue(result.summary, result.success)
            val settings = settingsDataStore.settings.first()
            assertEquals(ProviderType.OpenAiCompatible, settings.providerType)
            val endpointSettings = settings.endpointSettings(ProviderType.DeepSeek)
            assertEquals("https://proxy.example/v1", endpointSettings.baseUrl)
            assertEquals("deepseek-test", endpointSettings.modelId)
            assertEquals(120, endpointSettings.timeoutSeconds)
            val provider = result.payload.getValue("provider").jsonObject
            assertEquals("deepseek", provider.getValue("providerId").jsonPrimitive.content)
            assertEquals(
                "false",
                provider
                    .getValue("selected")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "https://proxy.example/v1",
                provider
                    .getValue("endpointSettings")
                    .jsonObject
                    .getValue("baseUrl")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `provider reset restores default endpoint settings without changing selected provider`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .copy(providerType = ProviderType.OpenAiCompatible)
                    .withEndpointSettings(
                        providerType = ProviderType.DeepSeek,
                        settings =
                            ProviderEndpointSettings(
                                baseUrl = "https://proxy.example/v1",
                                modelId = "deepseek-custom",
                                timeoutSeconds = 90,
                            ),
                    ),
            )
            val registry = buildRegistry()

            val reset =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "provider.defaults"),
                    arguments =
                        buildJsonObject {
                            put("providerId", "deepseek")
                        },
                )
            val rejectedFake =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "providers.reset"),
                    arguments =
                        buildJsonObject {
                            put("providerId", "fake")
                        },
                )

            assertTrue(reset.summary, reset.success)
            val settings = settingsDataStore.settings.first()
            assertEquals(ProviderType.OpenAiCompatible, settings.providerType)
            assertEquals(ProviderType.DeepSeek.defaultEndpointSettings(), settings.endpointSettings(ProviderType.DeepSeek))
            val provider = reset.payload.getValue("provider").jsonObject
            assertEquals("deepseek", provider.getValue("providerId").jsonPrimitive.content)
            assertEquals(
                ProviderType.DeepSeek.defaultBaseUrl,
                provider
                    .getValue("endpointSettings")
                    .jsonObject
                    .getValue("baseUrl")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                ProviderType.DeepSeek.defaultModelId,
                provider
                    .getValue("endpointSettings")
                    .jsonObject
                    .getValue("modelId")
                    .jsonPrimitive
                    .content,
            )
            assertFalse(rejectedFake.success)
            assertEquals("PROVIDER_NOT_CONFIGURABLE", rejectedFake.errorCode)
        }

    @Test
    fun `provider auth status reports configured secrets without exposing values`() =
        runTest {
            val providerSecretStore = FakeProviderSecretStore()
            providerSecretStore.writeApiKey(ProviderType.DeepSeek, "sk-secret-value")
            providerSecretStore.writeOAuthCredential(
                ProviderType.OpenAiCodex,
                ProviderOAuthCredential(
                    provider = ProviderType.OpenAiCodex.providerId,
                    accessToken = "access-secret",
                    refreshToken = "refresh-secret",
                    expiresAtEpochMillis = Instant.parse("2026-03-09T00:00:00Z").toEpochMilli(),
                    email = "user@example.test",
                ),
            )
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot().copy(providerType = ProviderType.DeepSeek),
            )
            val registry = buildRegistry(providerSecretStore = providerSecretStore)

            val allStatuses =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "providers.auth"),
                    arguments = buildJsonObject {},
                )
            val fakeStatus =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "provider.auth.status"),
                    arguments =
                        buildJsonObject {
                            put("providerId", "fake")
                        },
                )

            assertTrue(allStatuses.summary, allStatuses.success)
            val payloadText = allStatuses.payload.toString()
            assertFalse(payloadText.contains("sk-secret-value"))
            assertFalse(payloadText.contains("access-secret"))
            assertFalse(payloadText.contains("refresh-secret"))
            val providers = allStatuses.payload.getValue("providers").jsonArray
            val deepSeek =
                providers
                    .first { provider ->
                        provider.jsonObject
                            .getValue("providerId")
                            .jsonPrimitive.content == "deepseek"
                    }.jsonObject
            assertEquals("Configured", deepSeek.getValue("status").jsonPrimitive.content)
            assertEquals("true", deepSeek.getValue("configured").jsonPrimitive.content)
            assertEquals("true", deepSeek.getValue("apiKeyConfigured").jsonPrimitive.content)
            assertEquals("true", deepSeek.getValue("selected").jsonPrimitive.content)
            val codex =
                providers
                    .first { provider ->
                        provider.jsonObject
                            .getValue("providerId")
                            .jsonPrimitive.content == "openai-codex"
                    }.jsonObject
            assertEquals("Configured", codex.getValue("status").jsonPrimitive.content)
            assertEquals("true", codex.getValue("oauthConfigured").jsonPrimitive.content)
            assertEquals("2026-03-09T00:00:00Z", codex.getValue("oauthExpiresAtIso").jsonPrimitive.content)
            assertEquals("false", codex.getValue("oauthExpired").jsonPrimitive.content)

            assertTrue(fakeStatus.success)
            val fakeProvider =
                fakeStatus.payload
                    .getValue("providers")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("NotRequired", fakeProvider.getValue("status").jsonPrimitive.content)
            assertEquals("true", fakeProvider.getValue("configured").jsonPrimitive.content)
        }

    @Test
    fun `provider stats summarizes inventory endpoint customization and auth state`() =
        runTest {
            val providerSecretStore = FakeProviderSecretStore()
            providerSecretStore.writeApiKey(ProviderType.DeepSeek, "deepseek-secret")
            providerSecretStore.writeOAuthCredential(
                ProviderType.OpenAiCodex,
                ProviderOAuthCredential(
                    provider = ProviderType.OpenAiCodex.providerId,
                    accessToken = "codex-access-secret",
                    refreshToken = "codex-refresh-secret",
                    expiresAtEpochMillis = Instant.parse("2026-03-07T00:00:00Z").toEpochMilli(),
                    profileName = "Work",
                ),
            )
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .copy(providerType = ProviderType.OpenAiCodex)
                    .withEndpointSettings(
                        providerType = ProviderType.DeepSeek,
                        settings =
                            ProviderType.DeepSeek
                                .defaultEndpointSettings()
                                .copy(
                                    baseUrl = "https://proxy.example/v1",
                                    modelId = "deepseek-stats",
                                    timeoutSeconds = 42,
                                ),
                    ),
            )
            val registry = buildRegistry(providerSecretStore = providerSecretStore)

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "provider.stats"),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.summary, result.success)
            val payloadText = result.payload.toString()
            assertFalse(payloadText.contains("deepseek-secret"))
            assertFalse(payloadText.contains("codex-access-secret"))
            assertFalse(payloadText.contains("codex-refresh-secret"))
            assertEquals(
                ProviderType.entries.size.toString(),
                result.payload
                    .getValue("providerCount")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                ProviderType.OpenAiCodex.providerId,
                result.payload
                    .getValue("currentProviderId")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "true",
                result.payload
                    .getValue("secretStatusAvailable")
                    .jsonPrimitive
                    .content,
            )
            val endpointStats =
                result.payload
                    .getValue("endpointStats")
                    .jsonObject
            assertEquals("1", endpointStats.getValue("customBaseUrlProviderCount").jsonPrimitive.content)
            assertEquals("1", endpointStats.getValue("customModelIdProviderCount").jsonPrimitive.content)
            assertEquals("1", endpointStats.getValue("customTimeoutProviderCount").jsonPrimitive.content)

            val apiKeyStats =
                result.payload
                    .getValue("apiKeyStats")
                    .jsonObject
            assertEquals(
                ProviderType.entries
                    .count { provider -> provider.requiresApiKey }
                    .toString(),
                apiKeyStats
                    .getValue("apiKeyProviderCount")
                    .jsonPrimitive
                    .content,
            )
            assertEquals("1", apiKeyStats.getValue("apiKeyConfiguredProviderCount").jsonPrimitive.content)

            val oauthStats =
                result.payload
                    .getValue("oauthStats")
                    .jsonObject
            assertEquals("1", oauthStats.getValue("oauthProviderCount").jsonPrimitive.content)
            assertEquals("1", oauthStats.getValue("oauthConfiguredProviderCount").jsonPrimitive.content)
            assertEquals("1", oauthStats.getValue("oauthExpiredProviderCount").jsonPrimitive.content)
            assertEquals("1", oauthStats.getValue("oauthProfileConfiguredProviderCount").jsonPrimitive.content)

            val configuredAuthCount =
                result.payload
                    .getValue("authStatusStats")
                    .jsonArray
                    .first { stats ->
                        stats.jsonObject
                            .getValue("status")
                            .jsonPrimitive
                            .content == "Configured"
                    }.jsonObject
                    .getValue("providerCount")
                    .jsonPrimitive
                    .content
            assertEquals("2", configuredAuthCount)
        }

    @Test
    fun `tools list and get expose typed descriptors`() =
        runTest {
            val registry = buildRegistry()

            val listed =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tool.list"),
                    arguments = buildJsonObject {},
                )
            val loaded =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.get"),
                    arguments =
                        buildJsonObject {
                            put("toolName", "task.copy")
                        },
                )
            val loadedCreateByAlias =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.get"),
                    arguments =
                        buildJsonObject {
                            put("toolName", "task.create")
                        },
                )

            assertTrue(listed.success)
            val tools = listed.payload.getValue("tools").jsonArray
            assertTrue(
                tools.any { tool ->
                    tool.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content == "tools.get"
                },
            )
            assertTrue(
                tools.any { tool ->
                    tool.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content == "tasks.create"
                },
            )
            assertTrue(loaded.success)
            val tool = loaded.payload.getValue("tool").jsonObject
            assertEquals("tasks.duplicate", tool.getValue("name").jsonPrimitive.content)
            assertTrue(
                tool
                    .getValue("aliases")
                    .jsonArray
                    .any { alias -> alias.jsonPrimitive.content == "task.copy" },
            )
            assertTrue(
                tool
                    .getValue("inputSchema")
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
                    .containsKey("taskId"),
            )
            assertTrue(loadedCreateByAlias.success)
            assertEquals(
                "tasks.create",
                loadedCreateByAlias.payload
                    .getValue("tool")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content,
            )
            assertTrue(
                loadedCreateByAlias.payload
                    .getValue("tool")
                    .jsonObject
                    .getValue("aliases")
                    .jsonArray
                    .any { alias -> alias.jsonPrimitive.content == "task.create" },
            )
        }

    @Test
    fun `tools resolve reports canonical names and aliases explicitly`() =
        runTest {
            val registry = buildRegistry()

            val alias =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tool.alias"),
                    arguments =
                        buildJsonObject {
                            put("toolName", "task.copy")
                        },
                )
            val canonical =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.resolve"),
                    arguments =
                        buildJsonObject {
                            put("name", "tasks.create")
                        },
                )
            val missing =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.resolve"),
                    arguments =
                        buildJsonObject {
                            put("toolName", "missing.tool")
                        },
                )

            assertTrue(alias.success)
            assertEquals("task.copy", alias.payload["requestedName"]?.jsonPrimitive?.content)
            assertEquals("tasks.duplicate", alias.payload["canonicalName"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), alias.payload["isAlias"]?.jsonPrimitive?.content)
            assertEquals("task.copy", alias.payload["matchedAlias"]?.jsonPrimitive?.content)
            assertEquals("Available", alias.payload["availabilityStatus"]?.jsonPrimitive?.content)
            assertTrue(
                alias.payload
                    .getValue("aliases")
                    .jsonArray
                    .any { value -> value.jsonPrimitive.content == "task.copy" },
            )
            assertTrue(
                alias.payload
                    .getValue("arguments")
                    .jsonArray
                    .any { argument ->
                        argument.jsonObject
                            .getValue("name")
                            .jsonPrimitive
                            .content == "taskId"
                    },
            )

            assertTrue(canonical.success)
            assertEquals("tasks.create", canonical.payload["canonicalName"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), canonical.payload["isAlias"]?.jsonPrimitive?.content)
            assertEquals(JsonNull, canonical.payload["matchedAlias"])

            assertFalse(missing.success)
            assertEquals("TOOL_NOT_FOUND", missing.errorCode)
            assertEquals("missing.tool", missing.payload["toolName"]?.jsonPrimitive?.content)
        }

    @Test
    fun `tools validate dry runs target argument and availability checks`() =
        runTest {
            val registry = buildRegistry()

            val ready =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tool.check"),
                    arguments =
                        buildJsonObject {
                            put("toolName", "task.create")
                            put(
                                "arguments",
                                buildJsonObject {
                                    put("name", "Daily digest")
                                    put("prompt", "Summarize the day")
                                    put("scheduleKind", "once")
                                    put("extra", "ignored by registry")
                                },
                            )
                        },
                )
            val missing =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.validate"),
                    arguments =
                        buildJsonObject {
                            put("toolName", "tasks.create")
                            put(
                                "arguments",
                                buildJsonObject {
                                    put("name", "Incomplete task")
                                },
                            )
                        },
                )
            val invalidArguments =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.validate"),
                    arguments =
                        buildJsonObject {
                            put("toolName", "tasks.create")
                            put("arguments", "not an object")
                        },
                )
            val missingTool =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.validate"),
                    arguments =
                        buildJsonObject {
                            put("toolName", "missing.tool")
                        },
                )

            assertTrue(ready.success)
            assertEquals("task.create", ready.payload["requestedName"]?.jsonPrimitive?.content)
            assertEquals("tasks.create", ready.payload["canonicalName"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), ready.payload["isAlias"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), ready.payload["validArguments"]?.jsonPrimitive?.content)
            assertEquals(true.toString(), ready.payload["readyToExecute"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), ready.payload["semanticValidationIncluded"]?.jsonPrimitive?.content)
            assertTrue(
                ready.payload
                    .getValue("unknownArguments")
                    .jsonArray
                    .any { argument -> argument.jsonPrimitive.content == "extra" },
            )
            assertTrue(
                ready.payload
                    .getValue("argumentRequirements")
                    .jsonArray
                    .any { requirement ->
                        val payload = requirement.jsonObject
                        payload.getValue("name").jsonPrimitive.content == "prompt" &&
                            payload.getValue("provided").jsonPrimitive.content == true.toString()
                    },
            )

            assertTrue(missing.success)
            assertEquals(false.toString(), missing.payload["validArguments"]?.jsonPrimitive?.content)
            assertEquals(false.toString(), missing.payload["readyToExecute"]?.jsonPrimitive?.content)
            assertTrue(
                missing.payload
                    .getValue("missingRequiredArguments")
                    .jsonArray
                    .any { argument -> argument.jsonPrimitive.content == "prompt" },
            )
            assertTrue(
                missing.payload
                    .getValue("missingRequiredArguments")
                    .jsonArray
                    .any { argument -> argument.jsonPrimitive.content == "scheduleKind" },
            )

            assertFalse(invalidArguments.success)
            assertEquals("INVALID_ARGUMENTS", invalidArguments.errorCode)
            assertEquals("arguments", invalidArguments.payload["field"]?.jsonPrimitive?.content)

            assertFalse(missingTool.success)
            assertEquals("TOOL_NOT_FOUND", missingTool.errorCode)
            assertEquals("missing.tool", missingTool.payload["toolName"]?.jsonPrimitive?.content)
        }

    @Test
    fun `tools arguments summarizes and filters argument contract metadata`() =
        runTest {
            val registry = buildRegistry()

            val summary =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tool.arguments"),
                    arguments = buildJsonObject {},
                )
            val filtered =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.by_argument"),
                    arguments =
                        buildJsonObject {
                            put("argumentName", "taskId")
                            put("limit", "2")
                        },
                )
            val required =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.arguments"),
                    arguments =
                        buildJsonObject {
                            put("name", "taskId")
                            put("requiredOnly", "true")
                        },
                )
            val missing =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.arguments"),
                    arguments =
                        buildJsonObject {
                            put("argumentName", "missingArgument")
                        },
                )

            assertTrue(summary.success)
            assertEquals(JsonNull, summary.payload["argumentName"])
            val argumentStats = summary.payload.getValue("arguments").jsonArray
            val taskIdStats =
                argumentStats
                    .first { argument ->
                        argument.jsonObject
                            .getValue("name")
                            .jsonPrimitive
                            .content == "taskId"
                    }.jsonObject
            assertTrue(
                taskIdStats
                    .getValue("toolCount")
                    .jsonPrimitive
                    .content
                    .toInt() > 0,
            )
            assertTrue(taskIdStats.getValue("sampleTools").jsonArray.isNotEmpty())

            assertTrue(filtered.success)
            assertEquals("taskId", filtered.payload["argumentName"]?.jsonPrimitive?.content)
            assertEquals("2", filtered.payload["limit"]?.jsonPrimitive?.content)
            assertTrue(
                filtered.payload
                    .getValue("resultCount")
                    .jsonPrimitive
                    .content
                    .toInt() <= 2,
            )
            assertTrue(
                filtered.payload
                    .getValue("tools")
                    .jsonArray
                    .all { tool ->
                        tool.jsonObject
                            .getValue("matchingArguments")
                            .jsonArray
                            .any { argument ->
                                argument.jsonObject
                                    .getValue("name")
                                    .jsonPrimitive
                                    .content == "taskId"
                            }
                    },
            )

            assertTrue(required.success)
            assertTrue(
                required.payload
                    .getValue("tools")
                    .jsonArray
                    .flatMap { tool ->
                        tool.jsonObject
                            .getValue("matchingArguments")
                            .jsonArray
                    }.all { argument ->
                        argument.jsonObject
                            .getValue("required")
                            .jsonPrimitive
                            .content == true.toString()
                    },
            )

            assertTrue(missing.success)
            assertEquals("0", missing.payload["totalMatchCount"]?.jsonPrimitive?.content)
            assertEquals("0", missing.payload["resultCount"]?.jsonPrimitive?.content)
        }

    @Test
    fun `tools availability summarizes and filters readiness metadata`() =
        runTest {
            val registry = buildRegistry()

            val summary =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tool.availability"),
                    arguments = buildJsonObject {},
                )
            val available =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tool.status"),
                    arguments =
                        buildJsonObject {
                            put("status", "available")
                            put("limit", "3")
                        },
                )
            val invalid =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.availability"),
                    arguments =
                        buildJsonObject {
                            put("status", "not-a-status")
                        },
                )

            assertTrue(summary.success)
            assertEquals(JsonNull, summary.payload["availabilityStatus"])
            val availableStats =
                summary.payload
                    .getValue("statuses")
                    .jsonArray
                    .first { status ->
                        status.jsonObject
                            .getValue("status")
                            .jsonPrimitive
                            .content == "Available"
                    }.jsonObject
            assertTrue(
                availableStats
                    .getValue("toolCount")
                    .jsonPrimitive
                    .content
                    .toInt() > 0,
            )
            assertTrue(availableStats.getValue("sampleTools").jsonArray.isNotEmpty())

            assertTrue(available.success)
            assertEquals("Available", available.payload["availabilityStatus"]?.jsonPrimitive?.content)
            assertEquals("3", available.payload["limit"]?.jsonPrimitive?.content)
            assertTrue(
                available.payload
                    .getValue("resultCount")
                    .jsonPrimitive
                    .content
                    .toInt() <= 3,
            )
            assertTrue(
                available.payload
                    .getValue("tools")
                    .jsonArray
                    .all { tool ->
                        val toolPayload = tool.jsonObject
                        toolPayload
                            .getValue("availabilityStatus")
                            .jsonPrimitive
                            .content == "Available" &&
                            toolPayload.containsKey("requiredPermissions")
                    },
            )

            assertFalse(invalid.success)
            assertEquals("INVALID_ARGUMENTS", invalid.errorCode)
            assertEquals("status", invalid.payload["field"]?.jsonPrimitive?.content)
        }

    @Test
    fun `tools permissions summarizes and filters permission metadata`() =
        runTest {
            val registry = buildRegistry()

            val summary =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tool.permission"),
                    arguments = buildJsonObject {},
                )
            val filtered =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.by_permission"),
                    arguments =
                        buildJsonObject {
                            put("permission", "POST_NOTIFICATIONS")
                        },
                )
            val displayName =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.permissions"),
                    arguments =
                        buildJsonObject {
                            put("name", "Post notifications")
                        },
                )
            val missing =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.permissions"),
                    arguments =
                        buildJsonObject {
                            put("permission", "missing.permission")
                        },
                )

            assertTrue(summary.success)
            assertEquals(JsonNull, summary.payload["permission"])
            val permissionStats =
                summary.payload
                    .getValue("permissions")
                    .jsonArray
                    .first { permission ->
                        permission.jsonObject
                            .getValue("permission")
                            .jsonPrimitive
                            .content == "android.permission.POST_NOTIFICATIONS"
                    }.jsonObject
            assertTrue(
                permissionStats
                    .getValue("sampleTools")
                    .jsonArray
                    .any { tool -> tool.jsonPrimitive.content == "notifications.post" },
            )
            assertTrue(permissionStats.getValue("availabilityStats").jsonArray.isNotEmpty())

            assertTrue(filtered.success)
            assertEquals("POST_NOTIFICATIONS", filtered.payload["permission"]?.jsonPrimitive?.content)
            assertTrue(
                filtered.payload
                    .getValue("tools")
                    .jsonArray
                    .any { tool ->
                        val toolPayload = tool.jsonObject
                        toolPayload.getValue("name").jsonPrimitive.content == "notifications.post" &&
                            toolPayload
                                .getValue("matchingPermissions")
                                .jsonArray
                                .any { permission ->
                                    permission.jsonObject
                                        .getValue("permission")
                                        .jsonPrimitive
                                        .content == "android.permission.POST_NOTIFICATIONS"
                                }
                    },
            )

            assertTrue(displayName.success)
            assertTrue(
                displayName.payload
                    .getValue("tools")
                    .jsonArray
                    .isNotEmpty(),
            )

            assertTrue(missing.success)
            assertEquals("0", missing.payload["totalMatchCount"]?.jsonPrimitive?.content)
            assertEquals("0", missing.payload["resultCount"]?.jsonPrimitive?.content)
        }

    @Test
    fun `tools namespaces summarizes and filters canonical tool groups`() =
        runTest {
            val registry = buildRegistry()

            val summary =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tool.namespaces"),
                    arguments = buildJsonObject {},
                )
            val filtered =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.namespace"),
                    arguments =
                        buildJsonObject {
                            put("namespace", "tools")
                            put("limit", "4")
                        },
                )
            val missing =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.namespaces"),
                    arguments =
                        buildJsonObject {
                            put("name", "missing")
                        },
                )

            assertTrue(summary.success)
            assertEquals(JsonNull, summary.payload["namespace"])
            val tasksNamespace =
                summary.payload
                    .getValue("namespaces")
                    .jsonArray
                    .first { namespace ->
                        namespace.jsonObject
                            .getValue("namespace")
                            .jsonPrimitive
                            .content == "tasks"
                    }.jsonObject
            assertTrue(
                tasksNamespace
                    .getValue("sampleTools")
                    .jsonArray
                    .any { tool -> tool.jsonPrimitive.content == "tasks.create" },
            )
            assertTrue(tasksNamespace.getValue("availabilityStats").jsonArray.isNotEmpty())

            assertTrue(filtered.success)
            assertEquals("tools", filtered.payload["namespace"]?.jsonPrimitive?.content)
            assertEquals("tools", filtered.payload["canonicalNamespace"]?.jsonPrimitive?.content)
            assertEquals("4", filtered.payload["limit"]?.jsonPrimitive?.content)
            assertTrue(
                filtered.payload
                    .getValue("resultCount")
                    .jsonPrimitive
                    .content
                    .toInt() <= 4,
            )
            assertTrue(
                filtered.payload
                    .getValue("tools")
                    .jsonArray
                    .all { tool ->
                        tool.jsonObject
                            .getValue("namespace")
                            .jsonPrimitive
                            .content == "tools"
                    },
            )

            assertTrue(missing.success)
            assertEquals(JsonNull, missing.payload["canonicalNamespace"])
            assertEquals("0", missing.payload["totalMatchCount"]?.jsonPrimitive?.content)
            assertEquals("0", missing.payload["resultCount"]?.jsonPrimitive?.content)
        }

    @Test
    fun `tools stats summarizes registry metadata without schemas`() =
        runTest {
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tool.stats"),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            val descriptors = registry.descriptors()
            val payload = result.payload
            val toolsWithAliasesCount =
                payload
                    .getValue("toolsWithAliasesCount")
                    .jsonPrimitive
                    .content
                    .toInt()
            val aliasCount =
                payload
                    .getValue("aliasCount")
                    .jsonPrimitive
                    .content
                    .toInt()
            val toolsWithArgumentsCount =
                payload
                    .getValue("toolsWithArgumentsCount")
                    .jsonPrimitive
                    .content
                    .toInt()
            val totalArgumentCount =
                payload
                    .getValue("totalArgumentCount")
                    .jsonPrimitive
                    .content
                    .toInt()
            assertEquals(
                descriptors.size.toString(),
                payload
                    .getValue("toolCount")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "false",
                payload
                    .getValue("inputSchemaIncluded")
                    .jsonPrimitive
                    .content,
            )
            assertFalse(payload.containsKey("inputSchema"))
            assertTrue(toolsWithAliasesCount > 0)
            assertTrue(aliasCount > 0)
            assertTrue(toolsWithArgumentsCount > 0)
            assertTrue(totalArgumentCount > 0)
            assertTrue(
                payload
                    .getValue("availabilityStats")
                    .jsonArray
                    .any { stats ->
                        val statsPayload = stats.jsonObject
                        val toolCount =
                            statsPayload
                                .getValue("toolCount")
                                .jsonPrimitive
                                .content
                                .toInt()
                        statsPayload
                            .getValue("status")
                            .jsonPrimitive
                            .content == "Available" &&
                            toolCount > 0
                    },
            )
            assertTrue(
                payload
                    .getValue("permissionStats")
                    .jsonArray
                    .any { stats ->
                        stats.jsonObject
                            .getValue("permission")
                            .jsonPrimitive
                            .content
                            .isNotBlank()
                    },
            )
        }

    @Test
    fun `tools search returns matching descriptors`() =
        runTest {
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "tools.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "notification title")
                        },
                )

            assertTrue(result.success)
            val tools = result.payload.getValue("tools").jsonArray
            assertTrue(
                tools.any { tool ->
                    tool.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content == "notifications.post"
                },
            )
            val notificationTool =
                tools
                    .first { tool ->
                        tool.jsonObject
                            .getValue("name")
                            .jsonPrimitive.content == "notifications.post"
                    }.jsonObject
            assertTrue(
                notificationTool
                    .getValue("arguments")
                    .jsonArray
                    .any { argument ->
                        argument.jsonObject
                            .getValue("name")
                            .jsonPrimitive.content == "title"
                    },
            )
        }

    @Test
    fun `skills list returns eligibility metadata`() =
        runTest {
            val registry =
                buildRegistry(
                    bundledSkills =
                        listOf(
                            skillSnapshot(
                                id = "notify",
                                name = "notify",
                                commandDispatch = ai.androidclaw.runtime.skills.SkillCommandDispatch.Tool,
                                commandTool = "notifications.post",
                                eligibility =
                                    ai.androidclaw.runtime.skills.SkillEligibility(
                                        status = ai.androidclaw.runtime.skills.SkillEligibilityStatus.MissingTool,
                                        reasons = listOf("Tool blocked: notifications.post (Post notifications)"),
                                    ),
                            ),
                        ),
                )

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skills.list"),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals("1", result.payload["skillCount"]?.jsonPrimitive?.content)
            val skill =
                result.payload["skills"]
                    ?.jsonArray
                    ?.single()
                    ?.jsonObject
            assertEquals("notify", skill?.get("name")?.jsonPrimitive?.content)
            assertEquals("MissingTool", skill?.get("eligibilityStatus")?.jsonPrimitive?.content)
        }

    @Test
    fun `skills refresh forces inventory reload for active session`() =
        runTest {
            var capturedSessionId: String? = null
            var capturedForceRefresh: Boolean? = null
            val refreshedSkill =
                skillSnapshot(
                    id = "workspace-helper",
                    name = "workspace-helper",
                ).copy(
                    sourceType = ai.androidclaw.runtime.skills.SkillSourceType.Workspace,
                    workspaceSessionId = "session-1",
                )
            val registry =
                buildRegistry(
                    skillInventoryRefresher = { sessionId, forceRefresh ->
                        capturedSessionId = sessionId
                        capturedForceRefresh = forceRefresh
                        listOf(refreshedSkill)
                    },
                )

            val result =
                registry.execute(
                    context =
                        ToolExecutionContext.internal(
                            requestedName = "skill.rescan",
                            sessionId = "session-1",
                        ),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.summary, result.success)
            assertEquals("session-1", capturedSessionId)
            assertEquals(true, capturedForceRefresh)
            assertEquals("1", result.payload["skillCount"]?.jsonPrimitive?.content)
            assertEquals("session-1", result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("true", result.payload["forceRefresh"]?.jsonPrimitive?.content)
            val skill =
                result.payload
                    .getValue("skills")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("workspace-helper", skill.getValue("id").jsonPrimitive.content)
            assertEquals("Workspace", skill.getValue("sourceType").jsonPrimitive.content)
        }

    @Test
    fun `skills stats returns aggregate inventory metadata without instructions`() =
        runTest {
            val toolSkill =
                skillSnapshot(
                    id = "notify",
                    name = "notify",
                    commandDispatch = ai.androidclaw.runtime.skills.SkillCommandDispatch.Tool,
                    commandTool = "notifications.post",
                    eligibility =
                        ai.androidclaw.runtime.skills.SkillEligibility(
                            status = ai.androidclaw.runtime.skills.SkillEligibilityStatus.MissingTool,
                            reasons = listOf("Tool blocked: notifications.post"),
                        ),
                ).copy(
                    sourceType = ai.androidclaw.runtime.skills.SkillSourceType.Local,
                    secretStatuses = mapOf("NOTIFY_TOKEN" to false, "NOTIFY_ROOM" to true),
                    configStatuses = mapOf("notify.endpoint" to false, "notify.room" to true),
                )
            val shadowedInvalid =
                skillSnapshot(
                    id = "shadowed",
                    name = "shadowed",
                    eligibility =
                        ai.androidclaw.runtime.skills.SkillEligibility(
                            status = ai.androidclaw.runtime.skills.SkillEligibilityStatus.Invalid,
                            reasons = listOf("Invalid frontmatter"),
                        ),
                ).copy(
                    sourceType = ai.androidclaw.runtime.skills.SkillSourceType.Workspace,
                    frontmatter = null,
                    parseError = "Invalid frontmatter",
                    resolutionState = ai.androidclaw.runtime.skills.SkillResolutionState.Shadowed,
                    shadowedBy = "bundled-shadowed",
                )
            val registry =
                buildRegistry(
                    bundledSkills =
                        listOf(
                            skillSnapshot(id = "ready", name = "ready"),
                            skillSnapshot(id = "disabled", name = "disabled", enabled = false),
                            toolSkill,
                            shadowedInvalid,
                        ),
                )

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skill.stats"),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals("4", result.payload["skillCount"]?.jsonPrimitive?.content)
            assertEquals("3", result.payload["enabledSkillCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["disabledSkillCount"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["eligibleSkillCount"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["ineligibleSkillCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["modelReadySkillCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["toolDispatchSkillCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["missingFrontmatterCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["parseErrorCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["skillsWithSecretFieldsCount"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["totalSecretFieldCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["missingSecretFieldCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["skillsWithConfigFieldsCount"]?.jsonPrimitive?.content)
            assertEquals("2", result.payload["totalConfigFieldCount"]?.jsonPrimitive?.content)
            assertEquals("1", result.payload["missingConfigFieldCount"]?.jsonPrimitive?.content)
            val eligibilityStats =
                result.payload
                    .getValue("eligibilityStats")
                    .jsonArray
                    .associate { item ->
                        val payload = item.jsonObject
                        payload.getValue("eligibilityStatus").jsonPrimitive.content to
                            payload.getValue("skillCount").jsonPrimitive.content
                    }
            val dispatchStats =
                result.payload
                    .getValue("commandDispatchStats")
                    .jsonArray
                    .associate { item ->
                        val payload = item.jsonObject
                        payload.getValue("commandDispatch").jsonPrimitive.content to
                            payload.getValue("skillCount").jsonPrimitive.content
                    }
            val sourceStats =
                result.payload
                    .getValue("sourceTypeStats")
                    .jsonArray
                    .associate { item ->
                        val payload = item.jsonObject
                        payload.getValue("sourceType").jsonPrimitive.content to
                            payload.getValue("skillCount").jsonPrimitive.content
                    }
            assertEquals("2", eligibilityStats.getValue("Eligible"))
            assertEquals("1", eligibilityStats.getValue("Invalid"))
            assertEquals("1", eligibilityStats.getValue("MissingTool"))
            assertEquals("2", dispatchStats.getValue("Model"))
            assertEquals("1", dispatchStats.getValue("Tool"))
            assertEquals("1", dispatchStats.getValue("MissingFrontmatter"))
            assertEquals("2", sourceStats.getValue("Bundled"))
            assertEquals("1", sourceStats.getValue("Local"))
            assertEquals("1", sourceStats.getValue("Workspace"))
            assertFalse(result.payload.containsKey("instructionsMd"))
        }

    @Test
    fun `skills get returns frontmatter and instructions`() =
        runTest {
            val registry =
                buildRegistry(
                    bundledSkills =
                        listOf(
                            skillSnapshot(
                                id = "notify",
                                name = "notify",
                                commandDispatch = ai.androidclaw.runtime.skills.SkillCommandDispatch.Tool,
                                commandTool = "notifications.post",
                            ),
                        ),
                )

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skill.get"),
                    arguments =
                        buildJsonObject {
                            put("skillId", "notify")
                        },
                )

            assertTrue(result.success)
            val skill = result.payload.getValue("skill").jsonObject
            assertEquals("notify", skill.getValue("id").jsonPrimitive.content)
            assertEquals("notify", skill.getValue("name").jsonPrimitive.content)
            assertEquals("Do work", skill.getValue("instructionsMd").jsonPrimitive.content)
            assertEquals("true", skill.getValue("instructionsIncluded").jsonPrimitive.content)
            val frontmatter = skill.getValue("frontmatter").jsonObject
            assertEquals("Tool", frontmatter.getValue("commandDispatch").jsonPrimitive.content)
            assertEquals("notifications.post", frontmatter.getValue("commandTool").jsonPrimitive.content)
        }

    @Test
    fun `skills config get returns config values without secret values`() =
        runTest {
            val configSkill =
                skillSnapshot(
                    id = "configurable",
                    name = "configurable",
                ).copy(
                    secretStatuses = mapOf("API_TOKEN" to true, "ROOM_TOKEN" to false),
                    configStatuses = mapOf("endpoint" to true, "room" to false),
                )
            var inspectedSkillId: String? = null
            val registry =
                buildRegistry(
                    bundledSkills = listOf(configSkill),
                    skillConfigurationReader = { skill ->
                        inspectedSkillId = skill.id
                        ai.androidclaw.runtime.skills.SkillConfigurationSnapshot(
                            skillId = skill.id,
                            skillKey = skill.skillKey,
                            displayName = skill.displayName,
                            secretFields =
                                listOf(
                                    ai.androidclaw.runtime.skills.SkillSecretField(
                                        envName = "API_TOKEN",
                                        configured = true,
                                    ),
                                    ai.androidclaw.runtime.skills.SkillSecretField(
                                        envName = "ROOM_TOKEN",
                                        configured = false,
                                    ),
                                ),
                            configFields =
                                listOf(
                                    ai.androidclaw.runtime.skills.SkillConfigField(
                                        path = "endpoint",
                                        value = "https://example.test",
                                    ),
                                    ai.androidclaw.runtime.skills.SkillConfigField(
                                        path = "room",
                                        value = null,
                                    ),
                                ),
                            recoveryMessage = "Saved secret API_TOKEN could not be restored on this device.",
                        )
                    },
                )

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skill.config"),
                    arguments =
                        buildJsonObject {
                            put("skillId", "configurable")
                        },
                )

            assertTrue(result.summary, result.success)
            assertEquals("configurable", inspectedSkillId)
            val configuration = result.payload.getValue("configuration").jsonObject
            assertEquals("configurable", configuration.getValue("skillId").jsonPrimitive.content)
            assertEquals("2", configuration.getValue("secretFieldCount").jsonPrimitive.content)
            assertEquals("1", configuration.getValue("configuredSecretFieldCount").jsonPrimitive.content)
            assertEquals("2", configuration.getValue("configFieldCount").jsonPrimitive.content)
            assertEquals("1", configuration.getValue("configuredConfigFieldCount").jsonPrimitive.content)
            val secretFields = configuration.getValue("secretFields").jsonArray.map { it.jsonObject }
            assertEquals("API_TOKEN", secretFields[0].getValue("envName").jsonPrimitive.content)
            assertEquals("true", secretFields[0].getValue("configured").jsonPrimitive.content)
            assertFalse(secretFields[0].containsKey("value"))
            val configFields = configuration.getValue("configFields").jsonArray.map { it.jsonObject }
            assertEquals("endpoint", configFields[0].getValue("path").jsonPrimitive.content)
            assertEquals("https://example.test", configFields[0].getValue("value").jsonPrimitive.content)
            assertEquals("false", configFields[1].getValue("configured").jsonPrimitive.content)
        }

    @Test
    fun `skills config update sets and clears non secret config values`() =
        runTest {
            val configSkill =
                skillSnapshot(
                    id = "configurable",
                    name = "configurable",
                ).copy(
                    configStatuses = mapOf("endpoint" to false),
                )
            var storedValue: String? = null
            val registry =
                buildRegistry(
                    bundledSkills = listOf(configSkill),
                    skillConfigurationUpdater = { skill, configPath, value ->
                        storedValue = value
                        ai.androidclaw.runtime.skills.SkillConfigurationSnapshot(
                            skillId = skill.id,
                            skillKey = skill.skillKey,
                            displayName = skill.displayName,
                            configFields =
                                listOf(
                                    ai.androidclaw.runtime.skills.SkillConfigField(
                                        path = configPath,
                                        value = value,
                                    ),
                                ),
                        )
                    },
                )

            val setResult =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skill.config.set"),
                    arguments =
                        buildJsonObject {
                            put("skillId", "configurable")
                            put("path", "endpoint")
                            put("value", "https://example.test")
                        },
                )

            assertTrue(setResult.summary, setResult.success)
            assertEquals("https://example.test", storedValue)
            assertEquals(
                "false",
                setResult.payload
                    .getValue("cleared")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "endpoint",
                setResult.payload
                    .getValue("configPath")
                    .jsonPrimitive
                    .content,
            )
            val setConfigField =
                setResult.payload
                    .getValue("configuration")
                    .jsonObject
                    .getValue("configFields")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(
                "true",
                setConfigField
                    .getValue("configured")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "https://example.test",
                setConfigField
                    .getValue("value")
                    .jsonPrimitive
                    .content,
            )

            val clearResult =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skills.config.update"),
                    arguments =
                        buildJsonObject {
                            put("skillId", "configurable")
                            put("configPath", "endpoint")
                            put("clear", true)
                        },
                )

            assertTrue(clearResult.summary, clearResult.success)
            assertEquals(null, storedValue)
            assertEquals(
                "true",
                clearResult.payload
                    .getValue("cleared")
                    .jsonPrimitive
                    .content,
            )
            val clearedConfigField =
                clearResult.payload
                    .getValue("configuration")
                    .jsonObject
                    .getValue("configFields")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("false", clearedConfigField.getValue("configured").jsonPrimitive.content)
        }

    @Test
    fun `skills config update rejects undeclared config path`() =
        runTest {
            val registry =
                buildRegistry(
                    bundledSkills =
                        listOf(
                            skillSnapshot(
                                id = "configurable",
                                name = "configurable",
                            ).copy(
                                configStatuses = mapOf("endpoint" to false),
                            ),
                        ),
                )

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skills.config.update"),
                    arguments =
                        buildJsonObject {
                            put("skillId", "configurable")
                            put("configPath", "missing")
                            put("value", "ignored")
                        },
                )

            assertFalse(result.success)
            assertEquals("SKILL_CONFIG_NOT_FOUND", result.errorCode)
        }

    @Test
    fun `skills secret clear removes declared saved secret with confirmation`() =
        runTest {
            val secretSkill =
                skillSnapshot(
                    id = "secret-skill",
                    name = "secret-skill",
                ).copy(
                    secretStatuses = mapOf("API_TOKEN" to true),
                )
            var clearedEnvName: String? = null
            val registry =
                buildRegistry(
                    bundledSkills = listOf(secretSkill),
                    skillSecretClearer = { skill, envName ->
                        clearedEnvName = envName
                        ai.androidclaw.runtime.skills.SkillConfigurationSnapshot(
                            skillId = skill.id,
                            skillKey = skill.skillKey,
                            displayName = skill.displayName,
                            secretFields =
                                listOf(
                                    ai.androidclaw.runtime.skills.SkillSecretField(
                                        envName = envName,
                                        configured = false,
                                    ),
                                ),
                        )
                    },
                )

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skill.secret.clear"),
                    arguments =
                        buildJsonObject {
                            put("skillId", "secret-skill")
                            put("envName", "API_TOKEN")
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(result.summary, result.success)
            assertEquals("API_TOKEN", clearedEnvName)
            assertEquals(
                "API_TOKEN",
                result.payload
                    .getValue("envName")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "true",
                result.payload
                    .getValue("cleared")
                    .jsonPrimitive
                    .content,
            )
            val secretField =
                result.payload
                    .getValue("configuration")
                    .jsonObject
                    .getValue("secretFields")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("API_TOKEN", secretField.getValue("envName").jsonPrimitive.content)
            assertEquals("false", secretField.getValue("configured").jsonPrimitive.content)
            assertFalse(secretField.containsKey("value"))
        }

    @Test
    fun `skills secret clear requires confirmation`() =
        runTest {
            val registry =
                buildRegistry(
                    bundledSkills =
                        listOf(
                            skillSnapshot(
                                id = "secret-skill",
                                name = "secret-skill",
                            ).copy(
                                secretStatuses = mapOf("API_TOKEN" to true),
                            ),
                        ),
                )

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skills.secret.clear"),
                    arguments =
                        buildJsonObject {
                            put("skillId", "secret-skill")
                            put("envName", "API_TOKEN")
                        },
                )

            assertFalse(result.success)
            assertEquals("CONFIRMATION_REQUIRED", result.errorCode)
        }

    @Test
    fun `skills secret clear rejects undeclared secret`() =
        runTest {
            val registry =
                buildRegistry(
                    bundledSkills =
                        listOf(
                            skillSnapshot(
                                id = "secret-skill",
                                name = "secret-skill",
                            ).copy(
                                secretStatuses = mapOf("API_TOKEN" to true),
                            ),
                        ),
                )

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skills.secret.clear"),
                    arguments =
                        buildJsonObject {
                            put("skillId", "secret-skill")
                            put("envName", "MISSING_TOKEN")
                            put("confirm", "CONFIRM")
                        },
                )

            assertFalse(result.success)
            assertEquals("SKILL_SECRET_NOT_FOUND", result.errorCode)
        }

    @Test
    fun `skills search returns matching inventory entries`() =
        runTest {
            val registry =
                buildRegistry(
                    bundledSkills =
                        listOf(
                            skillSnapshot(
                                id = "notify",
                                name = "notify",
                                enabled = false,
                                commandDispatch = ai.androidclaw.runtime.skills.SkillCommandDispatch.Tool,
                                commandTool = "notifications.post",
                            ),
                            skillSnapshot(
                                id = "calendar",
                                name = "calendar",
                            ),
                        ),
                )

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skill.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "notify")
                        },
                )

            assertTrue(result.success)
            assertEquals(
                "1",
                result.payload
                    .getValue("resultCount")
                    .jsonPrimitive.content,
            )
            val skill =
                result.payload
                    .getValue("skills")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("notify", skill.getValue("id").jsonPrimitive.content)
            assertEquals("false", skill.getValue("enabled").jsonPrimitive.content)
            assertEquals("Tool", skill.getValue("commandDispatch").jsonPrimitive.content)
            assertEquals("notifications.post", skill.getValue("commandTool").jsonPrimitive.content)
        }

    @Test
    fun `skills enable and disable update skill state`() =
        runTest {
            var bundledSkills =
                listOf(
                    skillSnapshot(
                        id = "notify",
                        name = "notify",
                        enabled = false,
                    ),
                )
            val registry =
                buildRegistry(
                    bundledSkillsProvider = { bundledSkills },
                    skillEnabledUpdater = { skillId, enabled ->
                        bundledSkills =
                            bundledSkills.map { skill ->
                                if (skill.id == skillId) {
                                    skill.copy(enabled = enabled)
                                } else {
                                    skill
                                }
                            }
                    },
                )

            val enabled =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skill.enable"),
                    arguments =
                        buildJsonObject {
                            put("skillId", "notify")
                        },
                )
            val disabled =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "skills.disable"),
                    arguments =
                        buildJsonObject {
                            put("name", "notify")
                        },
                )

            assertTrue(enabled.summary, enabled.success)
            assertEquals(
                "true",
                enabled.payload
                    .getValue("skill")
                    .jsonObject
                    .getValue("enabled")
                    .jsonPrimitive.content,
            )
            assertTrue(disabled.summary, disabled.success)
            assertEquals(
                "false",
                disabled.payload
                    .getValue("skill")
                    .jsonObject
                    .getValue("enabled")
                    .jsonPrimitive.content,
            )
            assertFalse(bundledSkills.single().enabled)
        }

    @Test
    fun `health status reports selected provider and current tool availability`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot().copy(providerType = ProviderType.OpenAiCompatible),
            )
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "health.status"),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals("openai-compatible", result.payload["provider"]?.jsonPrimitive?.content)
            val tools = result.payload["tools"]?.jsonArray.orEmpty()
            assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "notifications.post" })
            assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "tasks.create" })
        }

    @Test
    fun `events recent exposes bounded diagnostics with optional details`() =
        runTest {
            val registry = buildRegistry()
            eventLogRepository.log(
                category = EventCategory.System,
                level = EventLevel.Info,
                message = "System started",
            )
            eventLogRepository.log(
                category = EventCategory.Provider,
                level = EventLevel.Error,
                message = "Provider offline",
                details = "{\"diagnostic\":\"network\"}",
            )

            val withoutDetails =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "logs.recent"),
                    arguments =
                        buildJsonObject {
                            put("category", "provider")
                            put("level", "error")
                            put("limit", 5)
                        },
                )
            val withDetails =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "events.recent"),
                    arguments =
                        buildJsonObject {
                            put("category", "provider")
                            put("includeDetails", true)
                        },
                )
            val invalidCategory =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "events.recent"),
                    arguments =
                        buildJsonObject {
                            put("category", "network")
                        },
                )

            assertTrue(withoutDetails.success)
            assertEquals("1", withoutDetails.payload["eventCount"]?.jsonPrimitive?.content)
            val event =
                withoutDetails.payload
                    .getValue("events")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("Provider", event.getValue("category").jsonPrimitive.content)
            assertEquals("Error", event.getValue("level").jsonPrimitive.content)
            assertEquals("Provider offline", event.getValue("message").jsonPrimitive.content)
            assertFalse(event.containsKey("details"))
            assertTrue(withDetails.success)
            val detailedEvent =
                withDetails.payload
                    .getValue("events")
                    .jsonArray
                    .first()
                    .jsonObject
            assertEquals("{\"diagnostic\":\"network\"}", detailedEvent.getValue("details").jsonPrimitive.content)
            assertFalse(invalidCategory.success)
            assertEquals("INVALID_ARGUMENTS", invalidCategory.errorCode)
        }

    @Test
    fun `events get loads exact event with optional details`() =
        runTest {
            val registry = buildRegistry()
            eventLogRepository.log(
                category = EventCategory.Provider,
                level = EventLevel.Error,
                message = "Provider offline",
                details = "{\"diagnostic\":\"network\"}",
            )
            val eventId =
                eventLogRepository
                    .observeRecent(limit = 10)
                    .first()
                    .single()
                    .id

            val withoutDetails =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "log.get"),
                    arguments =
                        buildJsonObject {
                            put("eventId", eventId)
                        },
                )
            val withDetails =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "events.get"),
                    arguments =
                        buildJsonObject {
                            put("eventId", eventId)
                            put("includeDetails", true)
                        },
                )
            val missing =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "events.get"),
                    arguments =
                        buildJsonObject {
                            put("eventId", "missing-event")
                        },
                )

            assertTrue(withoutDetails.success)
            val event =
                withoutDetails.payload
                    .getValue("event")
                    .jsonObject
            assertEquals(eventId, event.getValue("id").jsonPrimitive.content)
            assertEquals("Provider", event.getValue("category").jsonPrimitive.content)
            assertEquals("Error", event.getValue("level").jsonPrimitive.content)
            assertFalse(event.containsKey("details"))
            assertTrue(withDetails.success)
            assertEquals(
                "{\"diagnostic\":\"network\"}",
                withDetails.payload
                    .getValue("event")
                    .jsonObject
                    .getValue("details")
                    .jsonPrimitive.content,
            )
            assertFalse(missing.success)
            assertEquals("EVENT_NOT_FOUND", missing.errorCode)
        }

    @Test
    fun `events search finds recent diagnostics with filters and optional details`() =
        runTest {
            val registry = buildRegistry()
            eventLogRepository.log(
                category = EventCategory.Tool,
                level = EventLevel.Warn,
                message = "Tool retry scheduled",
                details = "{\"tool\":\"events.search\"}",
            )
            eventLogRepository.log(
                category = EventCategory.Provider,
                level = EventLevel.Error,
                message = "Provider offline",
                details = "{\"diagnostic\":\"network timeout\"}",
            )

            val withoutDetails =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "logs.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "timeout")
                            put("category", "provider")
                            put("level", "error")
                            put("limit", 10)
                        },
                )
            val withDetails =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "events.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "timeout")
                            put("includeDetails", true)
                        },
                )
            val noMatches =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "events.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "missing")
                        },
                )
            val invalidLevel =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "events.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "timeout")
                            put("level", "fatal")
                        },
                )

            assertTrue(withoutDetails.success)
            assertEquals("1", withoutDetails.payload["eventCount"]?.jsonPrimitive?.content)
            val event =
                withoutDetails.payload
                    .getValue("events")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("Provider", event.getValue("category").jsonPrimitive.content)
            assertEquals("Error", event.getValue("level").jsonPrimitive.content)
            assertEquals("Provider offline", event.getValue("message").jsonPrimitive.content)
            assertFalse(event.containsKey("details"))
            assertTrue(withDetails.success)
            assertEquals(
                "{\"diagnostic\":\"network timeout\"}",
                withDetails.payload
                    .getValue("events")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("details")
                    .jsonPrimitive.content,
            )
            assertTrue(noMatches.success)
            assertEquals("0", noMatches.payload["eventCount"]?.jsonPrimitive?.content)
            assertFalse(invalidLevel.success)
            assertEquals("INVALID_ARGUMENTS", invalidLevel.errorCode)
        }

    @Test
    fun `events stats summarizes recent diagnostics without exposing details`() =
        runTest {
            val registry = buildRegistry()
            eventLogRepository.log(
                category = EventCategory.System,
                level = EventLevel.Info,
                message = "System started",
                details = "{\"secret\":\"hidden\"}",
            )
            eventLogRepository.log(
                category = EventCategory.Provider,
                level = EventLevel.Error,
                message = "Provider offline",
                details = "{\"diagnostic\":\"network timeout\"}",
            )
            eventLogRepository.log(
                category = EventCategory.Provider,
                level = EventLevel.Warn,
                message = "Provider retrying",
                details = "{\"diagnostic\":\"retry\"}",
            )

            val providerStats =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "logs.stats"),
                    arguments =
                        buildJsonObject {
                            put("category", "provider")
                            put("scanLimit", 20)
                        },
                )
            val errorStats =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "events.stats"),
                    arguments =
                        buildJsonObject {
                            put("level", "error")
                        },
                )
            val invalidCategory =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "events.stats"),
                    arguments =
                        buildJsonObject {
                            put("category", "network")
                        },
                )

            assertTrue(providerStats.success)
            assertEquals("2", providerStats.payload["matchedEventCount"]?.jsonPrimitive?.content)
            assertEquals("Provider", providerStats.payload["category"]?.jsonPrimitive?.content)
            assertEquals("Any", providerStats.payload["level"]?.jsonPrimitive?.content)
            val providerCategoryCount =
                providerStats.payload
                    .getValue("countsByCategory")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("Provider", providerCategoryCount.getValue("category").jsonPrimitive.content)
            assertEquals("2", providerCategoryCount.getValue("count").jsonPrimitive.content)
            val levels =
                providerStats.payload
                    .getValue("countsByLevel")
                    .jsonArray
                    .map { item ->
                        val itemObject = item.jsonObject
                        val level =
                            itemObject
                                .getValue("level")
                                .jsonPrimitive
                                .content
                        val count =
                            itemObject
                                .getValue("count")
                                .jsonPrimitive
                                .content
                        level to count
                    }.toMap()
            assertEquals("1", levels.getValue("Error"))
            assertEquals("1", levels.getValue("Warn"))
            assertFalse(providerStats.payload.toString().contains("network timeout"))
            assertTrue(errorStats.success)
            assertEquals("1", errorStats.payload["matchedEventCount"]?.jsonPrimitive?.content)
            assertFalse(invalidCategory.success)
            assertEquals("INVALID_ARGUMENTS", invalidCategory.errorCode)
        }

    @Test
    fun `events trim deletes old diagnostics after explicit confirmation`() =
        runTest {
            val registry = buildRegistry()
            database.eventLogDao().insert(
                EventLogEntity(
                    id = "old-event",
                    timestamp = 1L,
                    category = "system",
                    level = "info",
                    message = "Old event",
                    detailsJson = null,
                ),
            )
            database.eventLogDao().insert(
                EventLogEntity(
                    id = "fresh-event",
                    timestamp = 3_000L,
                    category = "provider",
                    level = "warn",
                    message = "Fresh event",
                    detailsJson = null,
                ),
            )

            val rejected =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "events.trim"),
                    arguments =
                        buildJsonObject {
                            put("olderThanIso", "1970-01-01T00:00:02Z")
                            put("confirm", "no")
                        },
                )
            val trimmed =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "logs.trim"),
                    arguments =
                        buildJsonObject {
                            put("olderThanIso", "1970-01-01T00:00:02Z")
                            put("confirm", "CONFIRM")
                        },
                )
            val invalidCutoff =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "events.trim"),
                    arguments =
                        buildJsonObject {
                            put("olderThanIso", "not-a-timestamp")
                            put("confirm", "CONFIRM")
                        },
                )

            assertFalse(rejected.success)
            assertEquals("MISSING_TRIM_CONFIRMATION", rejected.errorCode)
            assertTrue(trimmed.success)
            assertEquals("1", trimmed.payload["deletedCount"]?.jsonPrimitive?.content)
            assertEquals("1970-01-01T00:00:02Z", trimmed.payload["olderThanIso"]?.jsonPrimitive?.content)
            val remainingIds =
                eventLogRepository
                    .observeRecent(limit = 20)
                    .first()
                    .map { event -> event.id }
            assertFalse(remainingIds.contains("old-event"))
            assertTrue(remainingIds.contains("fresh-event"))
            assertFalse(invalidCutoff.success)
            assertEquals("INVALID_ARGUMENTS", invalidCutoff.errorCode)
        }

    @Test
    fun `memory tools respect disabled state and store searchable manual memories`() =
        runTest {
            val registry = buildRegistry()

            val disabled =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.remember"),
                    arguments =
                        buildJsonObject {
                            put("text", "User prefers compact UI.")
                        },
                )

            assertFalse(disabled.success)
            assertEquals("MEMORY_DISABLED", disabled.errorCode)

            settingsDataStore.setMemoryEnabled(true)
            val remembered =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.remember", sessionId = "session-1"),
                    arguments =
                        buildJsonObject {
                            put("text", "User prefers compact UI.")
                        },
                )
            val searched =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "compact UI")
                        },
                )

            assertTrue(remembered.success)
            assertTrue(searched.success)
            assertEquals("1", searched.payload["memoryCount"]?.jsonPrimitive?.content)
            val memory =
                searched.payload
                    .getValue("memories")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("User prefers compact UI.", memory.getValue("text").jsonPrimitive.content)
            assertEquals("session-1", memory.getValue("sourceSessionId").jsonPrimitive.content)
        }

    @Test
    fun `memory session lists memories captured from one source session`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            val deleted =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User wants deleted session memory hidden.",
                        sourceSessionId = "session-5",
                    ),
                )
            val otherSession =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User wants another session memory listed separately.",
                        sourceSessionId = "session-other",
                    ),
                )
            val target =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User wants current session memory listed.",
                        sourceSessionId = "session-5",
                    ),
                )
            assertTrue(memoryRepository.delete(ownerUserId, deleted.id))

            val currentSession =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.session", sessionId = "session-5"),
                    arguments = buildJsonObject {},
                )
            val explicitSession =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memories.by_session"),
                    arguments =
                        buildJsonObject {
                            put("sourceSessionId", "session-other")
                        },
                )
            val commandSession =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command", sessionId = "session-5"),
                    arguments =
                        buildJsonObject {
                            put("command", "session")
                        },
                )
            val missingSession =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.session"),
                    arguments = buildJsonObject {},
                )

            assertTrue(currentSession.summary, currentSession.success)
            assertEquals("session-5", currentSession.payload["sourceSessionId"]?.jsonPrimitive?.content)
            assertEquals("1", currentSession.payload["memoryCount"]?.jsonPrimitive?.content)
            val currentMemories =
                currentSession.payload
                    .getValue("memories")
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(
                listOf(target.id),
                currentMemories.map { memory -> memory.getValue("id").jsonPrimitive.content },
            )
            assertEquals(
                "User wants current session memory listed.",
                currentMemories
                    .single()
                    .getValue("text")
                    .jsonPrimitive
                    .content,
            )
            assertFalse(currentMemories.single().containsKey("ownerUserId"))
            assertTrue(explicitSession.success)
            assertEquals(
                otherSession.id,
                explicitSession.payload
                    .getValue("memories")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
                    .content,
            )
            assertTrue(commandSession.success)
            assertEquals(
                target.id,
                commandSession.payload
                    .getValue("memories")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
                    .content,
            )
            assertFalse(missingSession.success)
            assertEquals("MISSING_MEMORY_SOURCE_SESSION_ID", missingSession.errorCode)
        }

    @Test
    fun `memory source lists memories captured by source type`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            val deletedAutomatic =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User wants deleted automatic memory hidden.",
                        sourceType = MemoryRepository.SOURCE_TYPE_AUTOMATIC,
                    ),
                )
            val manual =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User wants manual memory listed.",
                        sourceType = MemoryRepository.SOURCE_TYPE_MANUAL,
                    ),
                )
            val automatic =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User wants automatic memory listed.",
                        sourceType = MemoryRepository.SOURCE_TYPE_AUTOMATIC,
                    ),
                )
            assertTrue(memoryRepository.delete(ownerUserId, deletedAutomatic.id))

            val directAutomatic =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memories.by_source"),
                    arguments =
                        buildJsonObject {
                            put("sourceType", "auto")
                        },
                )
            val commandManual =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "source manual")
                        },
                )
            val missingSource =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.source"),
                    arguments = buildJsonObject {},
                )
            val invalidSource =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.source"),
                    arguments =
                        buildJsonObject {
                            put("sourceType", "external")
                        },
                )

            assertTrue(directAutomatic.summary, directAutomatic.success)
            assertEquals("automatic", directAutomatic.payload["sourceType"]?.jsonPrimitive?.content)
            assertEquals("1", directAutomatic.payload["memoryCount"]?.jsonPrimitive?.content)
            val automaticMemory =
                directAutomatic.payload
                    .getValue("memories")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(automatic.id, automaticMemory.getValue("id").jsonPrimitive.content)
            assertEquals("automatic", automaticMemory.getValue("sourceType").jsonPrimitive.content)
            assertFalse(automaticMemory.containsKey("ownerUserId"))
            assertTrue(commandManual.success)
            assertEquals("manual", commandManual.payload["sourceType"]?.jsonPrimitive?.content)
            assertEquals(
                manual.id,
                commandManual.payload
                    .getValue("memories")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
                    .content,
            )
            assertFalse(missingSource.success)
            assertEquals("MISSING_MEMORY_SOURCE_TYPE", missingSource.errorCode)
            assertFalse(invalidSource.success)
            assertEquals("INVALID_MEMORY_SOURCE_TYPE", invalidSource.errorCode)
        }

    @Test
    fun `memory message lists memories captured from one source message`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            val deleted =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User wants deleted source message memory hidden.",
                        sourceMessageIds = listOf("message-shared"),
                    ),
                )
            val otherMessage =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User wants another source message memory listed separately.",
                        sourceMessageIds = listOf("message-other"),
                    ),
                )
            val target =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User wants source message memory listed.",
                        sourceMessageIds = listOf("message-shared", "message-extra"),
                    ),
                )
            assertTrue(memoryRepository.delete(ownerUserId, deleted.id))

            val directMessage =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memories.by_message"),
                    arguments =
                        buildJsonObject {
                            put("messageId", "message-shared")
                        },
                )
            val commandMessage =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "message message-other")
                        },
                )
            val missingMessage =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.message"),
                    arguments = buildJsonObject {},
                )

            assertTrue(directMessage.summary, directMessage.success)
            assertEquals("message-shared", directMessage.payload["sourceMessageId"]?.jsonPrimitive?.content)
            assertEquals("1", directMessage.payload["memoryCount"]?.jsonPrimitive?.content)
            val directMemory =
                directMessage.payload
                    .getValue("memories")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(target.id, directMemory.getValue("id").jsonPrimitive.content)
            assertEquals(
                listOf("message-shared", "message-extra"),
                directMemory
                    .getValue("sourceMessageIds")
                    .jsonArray
                    .map { sourceId -> sourceId.jsonPrimitive.content },
            )
            assertFalse(directMemory.containsKey("ownerUserId"))
            assertTrue(commandMessage.success)
            assertEquals("message-other", commandMessage.payload["sourceMessageId"]?.jsonPrimitive?.content)
            assertEquals(
                otherMessage.id,
                commandMessage.payload
                    .getValue("memories")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
                    .content,
            )
            assertFalse(missingMessage.success)
            assertEquals("MISSING_MEMORY_SOURCE_MESSAGE_ID", missingMessage.errorCode)
        }

    @Test
    fun `memory get returns exact memory without exposing owner identifier`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            val stored =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User prefers green accent colors.",
                        sourceSessionId = "session-2",
                        sourceMessageIds = listOf("message-1", "message-2"),
                        sourceType = MemoryRepository.SOURCE_TYPE_AUTOMATIC,
                    ),
                )

            val directGet =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.get"),
                    arguments =
                        buildJsonObject {
                            put("id", stored.id)
                        },
                )
            val commandGet =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "get ${stored.id}")
                        },
                )
            val missingGet =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.get"),
                    arguments =
                        buildJsonObject {
                            put("id", "missing-memory")
                        },
                )

            assertTrue(directGet.success)
            assertEquals(stored.id, directGet.payload["id"]?.jsonPrimitive?.content)
            assertEquals("User prefers green accent colors.", directGet.payload["text"]?.jsonPrimitive?.content)
            assertEquals("session-2", directGet.payload["sourceSessionId"]?.jsonPrimitive?.content)
            assertEquals("automatic", directGet.payload["sourceType"]?.jsonPrimitive?.content)
            assertEquals(
                listOf("message-1", "message-2"),
                directGet.payload
                    .getValue("sourceMessageIds")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
            assertFalse(directGet.payload.containsKey("ownerUserId"))
            assertTrue(commandGet.success)
            assertEquals(stored.id, commandGet.payload["id"]?.jsonPrimitive?.content)
            assertFalse(missingGet.success)
            assertEquals("MEMORY_NOT_FOUND", missingGet.errorCode)
        }

    @Test
    fun `memory update replaces exact memory text through tool and command`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            val stored =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User prefers green accent colors.",
                        sourceSessionId = "session-3",
                    ),
                )

            val directUpdate =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.update"),
                    arguments =
                        buildJsonObject {
                            put("id", stored.id)
                            put("text", "User prefers purple accent colors.")
                        },
                )
            val commandUpdate =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "update ${stored.id} User prefers blue accent colors.")
                        },
                )
            val missingText =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.update"),
                    arguments =
                        buildJsonObject {
                            put("id", stored.id)
                        },
                )
            val commandMissingText =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "update ${stored.id}")
                        },
                )
            val missingMemory =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.update"),
                    arguments =
                        buildJsonObject {
                            put("id", "missing-memory")
                            put("text", "Replacement text.")
                        },
                )

            assertTrue(directUpdate.success)
            assertEquals(stored.id, directUpdate.payload["id"]?.jsonPrimitive?.content)
            assertEquals("User prefers purple accent colors.", directUpdate.payload["text"]?.jsonPrimitive?.content)
            assertEquals("session-3", directUpdate.payload["sourceSessionId"]?.jsonPrimitive?.content)
            assertFalse(directUpdate.payload.containsKey("ownerUserId"))
            assertTrue(commandUpdate.success)
            assertEquals("User prefers blue accent colors.", commandUpdate.payload["text"]?.jsonPrimitive?.content)
            assertEquals(
                "User prefers blue accent colors.",
                requireNotNull(memoryRepository.get(ownerUserId, stored.id)).text,
            )
            assertFalse(missingText.success)
            assertEquals("INVALID_ARGUMENTS", missingText.errorCode)
            assertFalse(commandMissingText.success)
            assertEquals("MISSING_MEMORY_TEXT", commandMissingText.errorCode)
            assertFalse(missingMemory.success)
            assertEquals("MEMORY_NOT_FOUND", missingMemory.errorCode)
        }

    @Test
    fun `memory status reports scope without exposing local install identifier`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.status"),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals("true", result.payload["enabled"]?.jsonPrimitive?.content)
            assertEquals("local-device", result.payload["scope"]?.jsonPrimitive?.content)
            assertFalse(result.payload.containsKey("installUserId"))
        }

    @Test
    fun `memory stats reports aggregate state without exposing owner identifier`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            val manual =
                requireNotNull(
                    memoryRepository.remember(
                        ownerUserId = ownerUserId,
                        text = "User likes green buttons.",
                        sourceSessionId = "session-4",
                        sourceType = MemoryRepository.SOURCE_TYPE_MANUAL,
                    ),
                )
            memoryRepository.remember(
                ownerUserId = ownerUserId,
                text = "User prefers compact layouts.",
                sourceType = MemoryRepository.SOURCE_TYPE_AUTOMATIC,
            )
            memoryRepository.delete(ownerUserId, manual.id)
            settingsDataStore.setMemoryEnabled(false)

            val directStats =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memories.stats"),
                    arguments = buildJsonObject {},
                )
            val commandStats =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "stats")
                        },
                )

            assertTrue(directStats.success)
            assertEquals("false", directStats.payload["enabled"]?.jsonPrimitive?.content)
            assertEquals("local-device", directStats.payload["scope"]?.jsonPrimitive?.content)
            assertEquals("1", directStats.payload["memoryCount"]?.jsonPrimitive?.content)
            assertEquals("1", directStats.payload["activeMemoryCount"]?.jsonPrimitive?.content)
            assertEquals("1", directStats.payload["deletedMemoryCount"]?.jsonPrimitive?.content)
            assertEquals("2", directStats.payload["totalMemoryCount"]?.jsonPrimitive?.content)
            assertFalse(directStats.payload.containsKey("installUserId"))
            val sourceStats =
                directStats.payload
                    .getValue("sourceTypeStats")
                    .jsonArray
                    .associate { item ->
                        val payload = item.jsonObject
                        payload.getValue("sourceType").jsonPrimitive.content to
                            payload.getValue("memoryCount").jsonPrimitive.content
                    }
            assertEquals("1", sourceStats.getValue(MemoryRepository.SOURCE_TYPE_AUTOMATIC))
            assertTrue(commandStats.success)
            assertEquals("1", commandStats.payload["activeMemoryCount"]?.jsonPrimitive?.content)
        }

    @Test
    fun `memory tools reject malformed or out of range limits`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)

            val malformedSearchLimit =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "compact UI")
                            put("limit", "abc")
                        },
                )
            val nonPositiveListLimit =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.list"),
                    arguments =
                        buildJsonObject {
                            put("limit", 0)
                        },
                )
            val oversizedSearchLimit =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.search"),
                    arguments =
                        buildJsonObject {
                            put("query", "compact UI")
                            put("limit", MemoryRepository.MAX_SEARCH_LIMIT + 1)
                        },
                )

            assertFalse(malformedSearchLimit.success)
            assertEquals("INVALID_MEMORY_LIMIT", malformedSearchLimit.errorCode)
            assertEquals("limit", malformedSearchLimit.payload["field"]?.jsonPrimitive?.content)
            assertFalse(nonPositiveListLimit.success)
            assertEquals("INVALID_MEMORY_LIMIT", nonPositiveListLimit.errorCode)
            assertFalse(oversizedSearchLimit.success)
            assertEquals("INVALID_MEMORY_LIMIT", oversizedSearchLimit.errorCode)
        }

    @Test
    fun `memory clear works while memory is disabled`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            memoryRepository.remember(ownerUserId, "User wants stored memory cleared.")
            settingsDataStore.setMemoryEnabled(false)

            val directClear =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.clear"),
                    arguments =
                        buildJsonObject {
                            put("confirm", "CONFIRM")
                        },
                )

            assertTrue(directClear.success)
            assertEquals("1", directClear.payload["deletedCount"]?.jsonPrimitive?.content)
            assertEquals(0, memoryRepository.countActive(ownerUserId))

            memoryRepository.remember(ownerUserId, "User wants slash clear to work too.")
            val commandClear =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "clear CONFIRM")
                        },
                )

            assertTrue(commandClear.success)
            assertEquals("1", commandClear.payload["deletedCount"]?.jsonPrimitive?.content)
            assertEquals(0, memoryRepository.countActive(ownerUserId))
        }

    @Test
    fun `memory delete works while memory is disabled`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            val directMemory =
                requireNotNull(
                    memoryRepository.remember(ownerUserId, "User wants one disabled memory deleted."),
                )
            settingsDataStore.setMemoryEnabled(false)

            val directDelete =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.delete"),
                    arguments =
                        buildJsonObject {
                            put("id", directMemory.id)
                        },
                )

            assertTrue(directDelete.success)
            assertEquals("true", directDelete.payload["deleted"]?.jsonPrimitive?.content)
            assertEquals(0, memoryRepository.countActive(ownerUserId))

            val commandMemory =
                requireNotNull(
                    memoryRepository.remember(ownerUserId, "User wants slash delete to work too."),
                )
            val commandDelete =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "delete ${commandMemory.id}")
                        },
                )

            assertTrue(commandDelete.success)
            assertEquals("true", commandDelete.payload["deleted"]?.jsonPrimitive?.content)
            assertEquals(0, memoryRepository.countActive(ownerUserId))
        }

    @Test
    fun `memory restore reactivates deleted memories while memory is enabled`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            val directMemory =
                requireNotNull(
                    memoryRepository.remember(ownerUserId, "User wants deleted memory restored."),
                )
            memoryRepository.delete(ownerUserId, directMemory.id)

            val directRestore =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memories.restore"),
                    arguments =
                        buildJsonObject {
                            put("id", directMemory.id)
                        },
                )
            val alreadyActive =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.restore"),
                    arguments =
                        buildJsonObject {
                            put("id", directMemory.id)
                        },
                )
            val commandMemory =
                requireNotNull(
                    memoryRepository.remember(ownerUserId, "User wants slash restore to work too."),
                )
            memoryRepository.delete(ownerUserId, commandMemory.id)
            val commandRestore =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "restore ${commandMemory.id}")
                        },
                )
            val disabledMemory =
                requireNotNull(
                    memoryRepository.remember(ownerUserId, "User does not want disabled restore."),
                )
            memoryRepository.delete(ownerUserId, disabledMemory.id)
            settingsDataStore.setMemoryEnabled(false)
            val disabledRestore =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.restore"),
                    arguments =
                        buildJsonObject {
                            put("id", disabledMemory.id)
                        },
                )

            assertTrue(directRestore.summary, directRestore.success)
            assertEquals(directMemory.id, directRestore.payload["id"]?.jsonPrimitive?.content)
            assertEquals("true", directRestore.payload["restored"]?.jsonPrimitive?.content)
            assertEquals(directMemory.id, requireNotNull(memoryRepository.get(ownerUserId, directMemory.id)).id)
            assertTrue(alreadyActive.success)
            assertEquals("false", alreadyActive.payload["restored"]?.jsonPrimitive?.content)
            assertTrue(commandRestore.success)
            assertEquals("true", commandRestore.payload["restored"]?.jsonPrimitive?.content)
            assertEquals(commandMemory.id, requireNotNull(memoryRepository.get(ownerUserId, commandMemory.id)).id)
            assertFalse(disabledRestore.success)
            assertEquals("MEMORY_DISABLED", disabledRestore.errorCode)
        }

    @Test
    fun `memory deleted lists restore candidates while memory is enabled`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            val firstDeleted =
                requireNotNull(
                    memoryRepository.remember(ownerUserId, "User wants first deleted memory listed."),
                )
            val secondDeleted =
                requireNotNull(
                    memoryRepository.remember(ownerUserId, "User wants second deleted memory listed."),
                )
            memoryRepository.remember(ownerUserId, "User wants active memory hidden from trash.")
            memoryRepository.delete(ownerUserId, firstDeleted.id)
            memoryRepository.delete(ownerUserId, secondDeleted.id)

            val directDeleted =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.trash"),
                    arguments =
                        buildJsonObject {
                            put("limit", 2)
                        },
                )
            val commandDeleted =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "deleted")
                        },
                )
            settingsDataStore.setMemoryEnabled(false)
            val disabledDeleted =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memories.deleted"),
                    arguments = buildJsonObject {},
                )

            assertTrue(directDeleted.summary, directDeleted.success)
            assertEquals("2", directDeleted.payload["memoryCount"]?.jsonPrimitive?.content)
            val deletedMemories =
                directDeleted.payload
                    .getValue("memories")
                    .jsonArray
                    .map { memory -> memory.jsonObject }
            val deletedIds = deletedMemories.map { memory -> memory.getValue("id").jsonPrimitive.content }.toSet()
            assertEquals(setOf(firstDeleted.id, secondDeleted.id), deletedIds)
            assertTrue(deletedMemories.all { memory -> memory.containsKey("deletedAt") })
            assertTrue(deletedMemories.none { memory -> memory.containsKey("ownerUserId") })
            assertTrue(commandDeleted.success)
            assertEquals("2", commandDeleted.payload["memoryCount"]?.jsonPrimitive?.content)
            assertFalse(disabledDeleted.success)
            assertEquals("MEMORY_DISABLED", disabledDeleted.errorCode)
        }

    @Test
    fun `memory command dispatch supports remember list and clear confirmation`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)

            val remembered =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "remember User uses Android Studio.")
                        },
                )
            val listed =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "list")
                        },
                )
            val rejectedClear =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "clear")
                        },
                )
            val cleared =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "clear CONFIRM")
                        },
                )

            assertTrue(remembered.success)
            assertTrue(listed.success)
            assertEquals("1", listed.payload["memoryCount"]?.jsonPrimitive?.content)
            assertFalse(rejectedClear.success)
            assertEquals("MISSING_CLEAR_CONFIRMATION", rejectedClear.errorCode)
            assertTrue(cleared.success)
            assertEquals("1", cleared.payload["deletedCount"]?.jsonPrimitive?.content)
        }

    @Test
    fun `memory command validates required search and delete operands`() =
        runTest {
            val registry = buildRegistry()
            settingsDataStore.setMemoryEnabled(true)

            val rejectedSearch =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "search")
                        },
                )
            val rejectedDelete =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "memory.command"),
                    arguments =
                        buildJsonObject {
                            put("command", "delete")
                        },
                )

            assertFalse(rejectedSearch.success)
            assertEquals("MISSING_MEMORY_QUERY", rejectedSearch.errorCode)
            assertFalse(rejectedDelete.success)
            assertEquals("MISSING_MEMORY_ID", rejectedDelete.errorCode)
        }

    private fun buildRegistry(
        bundledSkills: List<ai.androidclaw.runtime.skills.SkillSnapshot> = emptyList(),
        bundledSkillsProvider: suspend () -> List<ai.androidclaw.runtime.skills.SkillSnapshot> = { bundledSkills },
        skillEnabledUpdater: suspend (skillId: String, enabled: Boolean) -> Unit = { _, _ -> },
        skillInventoryRefresher: suspend (sessionId: String?, forceRefresh: Boolean) -> List<ai.androidclaw.runtime.skills.SkillSnapshot> =
            { _, _ -> bundledSkillsProvider() },
        skillConfigurationReader: suspend (ai.androidclaw.runtime.skills.SkillSnapshot) -> ai.androidclaw.runtime.skills.SkillConfigurationSnapshot =
            { skill ->
                ai.androidclaw.runtime.skills.SkillConfigurationSnapshot(
                    skillId = skill.id,
                    skillKey = skill.skillKey,
                    displayName = skill.displayName,
                )
            },
        skillConfigurationUpdater: suspend (
            ai.androidclaw.runtime.skills.SkillSnapshot,
            String,
            String?,
        ) -> ai.androidclaw.runtime.skills.SkillConfigurationSnapshot = { skill, configPath, value ->
            ai.androidclaw.runtime.skills.SkillConfigurationSnapshot(
                skillId = skill.id,
                skillKey = skill.skillKey,
                displayName = skill.displayName,
                configFields =
                    listOf(
                        ai.androidclaw.runtime.skills.SkillConfigField(
                            path = configPath,
                            value = value,
                        ),
                    ),
            )
        },
        skillSecretClearer: suspend (
            ai.androidclaw.runtime.skills.SkillSnapshot,
            String,
        ) -> ai.androidclaw.runtime.skills.SkillConfigurationSnapshot = { skill, envName ->
            ai.androidclaw.runtime.skills.SkillConfigurationSnapshot(
                skillId = skill.id,
                skillKey = skill.skillKey,
                displayName = skill.displayName,
                secretFields =
                    listOf(
                        ai.androidclaw.runtime.skills.SkillSecretField(
                            envName = envName,
                            configured = false,
                        ),
                    ),
            )
        },
        providerSecretStore: ProviderSecretStore? = null,
    ): ToolRegistry =
        createBuiltInToolRegistry(
            application = application,
            settingsDataStore = settingsDataStore,
            sessionRepository = sessionRepository,
            taskRepository = taskRepository,
            schedulerCoordinator = schedulerCoordinator,
            bundledSkillsProvider = bundledSkillsProvider,
            skillEnabledUpdater = skillEnabledUpdater,
            skillInventoryRefresher = skillInventoryRefresher,
            skillConfigurationReader = skillConfigurationReader,
            skillConfigurationUpdater = skillConfigurationUpdater,
            skillSecretClearer = skillSecretClearer,
            providerSecretStore = providerSecretStore,
            messageRepository = messageRepository,
            memoryRepository = memoryRepository,
            eventLogRepository = eventLogRepository,
            clock = testClock,
        )
}

private fun toolTestMessageEntity(
    id: String,
    sessionId: String,
    role: String,
    content: String,
    createdAt: Long,
): MessageEntity =
    MessageEntity(
        id = id,
        sessionId = sessionId,
        role = role,
        content = content,
        createdAt = createdAt,
        providerMeta = null,
        toolCallId = null,
        taskRunId = null,
    )

private class FakeProviderSecretStore : ProviderSecretStore {
    private val apiKeys = mutableMapOf<ProviderType, String?>()
    private val oAuthCredentials = mutableMapOf<ProviderType, ProviderOAuthCredential?>()

    override suspend fun readApiKey(providerType: ProviderType): String? = apiKeys[providerType]

    override suspend fun writeApiKey(
        providerType: ProviderType,
        apiKey: String?,
    ) {
        apiKeys[providerType] = apiKey
    }

    override suspend fun readOAuthCredential(providerType: ProviderType): ProviderOAuthCredential? = oAuthCredentials[providerType]

    override suspend fun writeOAuthCredential(
        providerType: ProviderType,
        credential: ProviderOAuthCredential?,
    ) {
        oAuthCredentials[providerType] = credential
    }

    override suspend fun consumeRecoveryNotice(providerType: ProviderType): Boolean = false
}

private fun skillSnapshot(
    id: String,
    name: String,
    enabled: Boolean = true,
    commandDispatch: ai.androidclaw.runtime.skills.SkillCommandDispatch = ai.androidclaw.runtime.skills.SkillCommandDispatch.Model,
    commandTool: String? = null,
    eligibility: ai.androidclaw.runtime.skills.SkillEligibility =
        ai.androidclaw.runtime.skills.SkillEligibility(
            ai.androidclaw.runtime.skills.SkillEligibilityStatus.Eligible,
        ),
): ai.androidclaw.runtime.skills.SkillSnapshot =
    ai.androidclaw.runtime.skills.SkillSnapshot(
        id = id,
        skillKey = name,
        sourceType = ai.androidclaw.runtime.skills.SkillSourceType.Bundled,
        baseDir = "asset://skills/$id",
        enabled = enabled,
        frontmatter =
            ai.androidclaw.runtime.skills.SkillFrontmatter(
                name = name,
                description = "Description for $name",
                homepage = null,
                userInvocable = true,
                disableModelInvocation = false,
                commandDispatch = commandDispatch,
                commandTool = commandTool,
                commandArgMode = "raw",
                metadata = null,
                unknownFields = emptyMap(),
            ),
        instructionsMd = "Do work",
        eligibility = eligibility,
    )
