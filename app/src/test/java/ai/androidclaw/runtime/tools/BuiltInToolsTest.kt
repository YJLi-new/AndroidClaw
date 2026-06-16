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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
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
            providerSecretStore = providerSecretStore,
            messageRepository = messageRepository,
            memoryRepository = memoryRepository,
            eventLogRepository = eventLogRepository,
            clock = testClock,
        )
}

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
