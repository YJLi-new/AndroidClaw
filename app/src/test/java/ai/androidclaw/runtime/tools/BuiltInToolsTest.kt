package ai.androidclaw.runtime.tools

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.data.repository.EventLogRepository
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
    private lateinit var taskRepository: TaskRepository
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
            taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
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
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
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
    fun `sessions compact stores explicit summary for active session`() =
        runTest {
            val session = sessionRepository.getOrCreateMainSession()
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.compact", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("command", "Goal: finish /compact. Next: validate and push.")
                        },
                )

            assertTrue(result.success)
            assertEquals("Compacted this session with an explicit summary.", result.summary)
            assertEquals(session.id, result.payload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("Goal: finish /compact. Next: validate and push.", result.payload["summaryText"]?.jsonPrimitive?.content)
            assertEquals("Goal: finish /compact. Next: validate and push.", sessionRepository.getSession(session.id)?.summaryText)
        }

    @Test
    fun `sessions compact requires explicit summary text`() =
        runTest {
            val session = sessionRepository.getOrCreateMainSession()
            val registry = buildRegistry()

            val result =
                registry.execute(
                    context = ToolExecutionContext.internal(requestedName = "sessions.compact", sessionId = session.id),
                    arguments =
                        buildJsonObject {
                            put("command", "   ")
                        },
                )

            assertFalse(result.success)
            assertEquals("MISSING_SUMMARY", result.errorCode)
            assertEquals(null, sessionRepository.getSession(session.id)?.summaryText)
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
