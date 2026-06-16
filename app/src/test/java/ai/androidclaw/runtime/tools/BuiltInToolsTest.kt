package ai.androidclaw.runtime.tools

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
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
    ): ToolRegistry =
        createBuiltInToolRegistry(
            application = application,
            settingsDataStore = settingsDataStore,
            sessionRepository = sessionRepository,
            taskRepository = taskRepository,
            schedulerCoordinator = schedulerCoordinator,
            bundledSkillsProvider = { bundledSkills },
            messageRepository = messageRepository,
            memoryRepository = memoryRepository,
            eventLogRepository = eventLogRepository,
            clock = testClock,
        )
}

private fun skillSnapshot(
    id: String,
    name: String,
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
        enabled = true,
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
