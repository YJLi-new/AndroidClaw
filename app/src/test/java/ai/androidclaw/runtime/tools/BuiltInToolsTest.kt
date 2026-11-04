package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.WorkspaceStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.feature.chat.localizedToolHeadline
import ai.androidclaw.feature.chat.parseToolResultView
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.providers.NetworkStatusSnapshot
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.skills.testSkillSnapshot
import android.content.ClipboardManager
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

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
    private lateinit var schedulerCoordinator: SchedulerCoordinator
    private lateinit var workspaceStore: WorkspaceStore
    private var foreground = true
    private var skills: List<SkillSnapshot> = emptyList()
    private val skillToggles = mutableListOf<Pair<String, Boolean>>()

    @Before
    fun setUp() =
        runTest {
            application = ApplicationProvider.getApplicationContext()
            WorkManagerTestInitHelper.initializeTestWorkManager(application, Configuration.Builder().build())
            database = buildTestDatabase(application)
            sessionRepository = SessionRepository(database.sessionDao())
            messageRepository = MessageRepository(database.messageDao())
            taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
            memoryRepository = MemoryRepository(database.memoryItemDao())
            settingsDataStore = SettingsDataStore(application)
            schedulerCoordinator =
                SchedulerCoordinator(
                    application = application,
                    clock = testClock,
                    taskRepository = taskRepository,
                    eventLogRepository = EventLogRepository(database.eventLogDao()),
                )
            workspaceStore = WorkspaceStore(File(application.filesDir, "tool-test-workspace").apply { deleteRecursively() })
            settingsDataStore.setMemoryEnabled(false)
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.setMemoryEnabled(false)
            memoryRepository.clear(settingsDataStore.memorySettingsSnapshot().installUserId)
            database.close()
        }

    // --- sessions ---------------------------------------------------------------------------

    @Test
    fun `sessions list marks main and current sessions`() =
        runTest {
            val main = sessionRepository.getOrCreateMainSession()
            val project = sessionRepository.createSession("Project X")

            val result = run("sessions_list", sessionId = project.id)

            assertTrue(result.success)
            assertTrue(result.summary.contains("Main session [main]"))
            assertTrue(result.summary.contains("Project X [current] id=${project.id}"))
            assertTrue(result.summary.contains(main.id))
        }

    @Test
    fun `sessions history hides tool rows unless asked and resolves main`() =
        runTest {
            val main = sessionRepository.getOrCreateMainSession()
            messageRepository.addMessage(main.id, MessageRole.User, "hello there")
            messageRepository.addMessage(main.id, MessageRole.ToolCall, "Tool request: tasks_list {}", toolCallId = "c1")
            messageRepository.addMessage(main.id, MessageRole.Assistant, "hi")

            val plain = run("sessions_history", args { put("sessionId", "main") })
            val withTools =
                run(
                    "sessions_history",
                    args {
                        put("sessionId", "main")
                        put("includeTools", true)
                    },
                )

            val plainMessages = plain.payload.getValue("messages").jsonArray
            assertEquals(
                listOf("user", "assistant"),
                plainMessages.map {
                    it.jsonObject
                        .getValue("role")
                        .jsonPrimitive.content
                },
            )
            assertEquals(
                3,
                withTools.payload
                    .getValue("messages")
                    .jsonArray.size,
            )
            assertTrue(plain.toModelContent().contains("hello there"))
        }

    @Test
    fun `sessions create rename and send`() =
        runTest {
            val main = sessionRepository.getOrCreateMainSession()
            val created = run("sessions_create", args { put("title", "Trip planning") })
            val newId =
                created.payload
                    .getValue("id")
                    .jsonPrimitive.content

            val renamed = run("sessions_rename", args { put("title", "Trip to Kyoto") }, sessionId = newId)
            val sent =
                run(
                    "sessions_send",
                    args {
                        put("sessionId", "main")
                        put("text", "Flights booked.")
                    },
                    sessionId = newId,
                )
            val sameSession =
                run(
                    "sessions_send",
                    args {
                        put("sessionId", newId)
                        put("text", "x")
                    },
                    sessionId = newId,
                )

            assertTrue(renamed.success)
            assertEquals("Trip to Kyoto", sessionRepository.getSession(newId)?.title)
            assertTrue(sent.success)
            val posted = messageRepository.getRecentMessages(main.id, 1).single()
            assertEquals(MessageRole.Assistant, posted.role)
            assertEquals("[From Trip to Kyoto] Flights booked.", posted.content)
            assertEquals("SAME_SESSION", sameSession.errorCode)
        }

    @Test
    fun `unknown session ids fail cleanly`() =
        runTest {
            val result = run("sessions_history", args { put("sessionId", "missing") })

            assertFalse(result.success)
            assertEquals("SESSION_NOT_FOUND", result.errorCode)
        }

    // --- memory -----------------------------------------------------------------------------

    @Test
    fun `memory tools respect the memory switch`() =
        runTest {
            val disabled = run("memory_remember", args { put("text", "User likes tea") })
            assertEquals("MEMORY_DISABLED", disabled.errorCode)

            settingsDataStore.setMemoryEnabled(true)
            val stored = run("memory_remember", args { put("text", "User likes green tea") })
            val id = stored.summary.substringAfter("id=").substringBefore(")")
            val found = run("memory_search", args { put("query", "tea") })
            val fetched = run("memory_get", args { put("id", id) })
            val forgotten = run("memory_forget", args { put("id", id) })
            val missing = run("memory_get", args { put("id", id) })

            assertTrue(stored.success)
            assertTrue(found.summary.contains("User likes green tea"))
            assertTrue(fetched.summary.contains("User likes green tea"))
            assertTrue(forgotten.success)
            assertEquals("MEMORY_NOT_FOUND", missing.errorCode)
        }

    // --- tasks ------------------------------------------------------------------------------

    @Test
    fun `tasks create accepts local date times and lists the task`() =
        runTest {
            val created =
                run(
                    "tasks_create",
                    args {
                        put("name", "Water plants")
                        put("prompt", "Remind me to water the plants")
                        put("scheduleKind", "once")
                        put("atIso", "2026-03-09T09:30")
                    },
                )

            assertTrue(created.summary, created.success)
            val task = taskRepository.observeTasks().first().single()
            assertEquals(TaskSchedule.Once(Instant.parse("2026-03-09T09:30:00Z")), task.schedule)
            assertEquals(TaskExecutionMode.MainSession, task.executionMode)
            assertEquals(sessionRepository.getOrCreateMainSession().id, task.targetSessionId)
            assertTrue(run("tasks_list").summary.contains("Water plants id=${task.id}"))
        }

    @Test
    fun `tasks create infers cron and rejects short intervals`() =
        runTest {
            val cron =
                run(
                    "tasks_create",
                    args {
                        put("name", "Morning brief")
                        put("prompt", "Summarize my day")
                        put("cronExpression", "0 8 * * 1-5")
                        put("executionMode", "ISOLATED_SESSION")
                    },
                )
            val tooShort =
                run(
                    "tasks_create",
                    args {
                        put("name", "Spam")
                        put("prompt", "x")
                        put("scheduleKind", "interval")
                        put("repeatEveryMinutes", 5)
                    },
                )

            assertTrue(cron.summary, cron.success)
            val task = taskRepository.observeTasks().first().single()
            assertTrue(task.schedule is TaskSchedule.Cron)
            assertEquals(TaskExecutionMode.IsolatedSession, task.executionMode)
            assertEquals("INVALID_ARGUMENTS", tooShort.errorCode)
            assertTrue(tooShort.summary.contains("at least 15"))
        }

    @Test
    fun `tasks update pauses and legacy enable alias resumes`() =
        runTest {
            val taskId = createIntervalTask()

            val paused =
                run(
                    "tasks_update",
                    args {
                        put("taskId", taskId)
                        put("enabled", false)
                        put("name", "Renamed")
                    },
                )
            assertTrue(paused.success)
            assertEquals(false, taskRepository.getTask(taskId)?.enabled)
            assertEquals("Renamed", taskRepository.getTask(taskId)?.name)

            val resumed = run("tasks.enable", args { put("taskId", taskId) })
            assertTrue(resumed.success)
            assertEquals(true, taskRepository.getTask(taskId)?.enabled)
        }

    @Test
    fun `tasks run now runs and delete`() =
        runTest {
            val taskId = createIntervalTask()

            assertTrue(run("tasks_run_now", args { put("taskId", taskId) }).success)
            assertEquals("No task runs recorded yet.", run("tasks_runs", args { put("taskId", taskId) }).summary)
            assertTrue(run("tasks_delete", args { put("taskId", taskId) }).success)
            assertNull(taskRepository.getTask(taskId))
            assertEquals("TASK_NOT_FOUND", run("tasks_delete", args { put("taskId", taskId) }).errorCode)
        }

    // --- skills -----------------------------------------------------------------------------

    @Test
    fun `skills list get and toggle`() =
        runTest {
            skills =
                listOf(
                    testSkillSnapshot(id = "bundled:weather", name = "weather").copy(instructionsMd = "Use web_fetch for forecasts."),
                )

            val listed = run("skills_list")
            val fetched = run("skills_get", args { put("name", "/weather") })
            val toggled =
                run(
                    "skills_set_enabled",
                    args {
                        put("name", "weather")
                        put("enabled", false)
                    },
                )
            val missing = run("skills_get", args { put("name", "nope") })

            assertTrue(listed.summary.contains("weather"))
            assertEquals(
                "Use web_fetch for forecasts.",
                fetched.payload
                    .getValue("instructions")
                    .jsonPrimitive.content,
            )
            assertTrue(toggled.success)
            assertEquals(listOf("bundled:weather" to false), skillToggles)
            assertEquals("SKILL_NOT_FOUND", missing.errorCode)
        }

    // --- workspace --------------------------------------------------------------------------

    @Test
    fun `workspace starts with persona files and supports write read edit`() =
        runTest {
            val listed = run("workspace_list")
            assertTrue(listed.summary.contains("AGENTS.md"))
            assertTrue(listed.summary.contains("MEMORY.md"))

            assertTrue(
                run(
                    "workspace_write",
                    args {
                        put("path", "notes/todo.md")
                        put("content", "- buy milk\n")
                    },
                ).success,
            )
            assertTrue(
                run(
                    "workspace_write",
                    args {
                        put("path", "notes/todo.md")
                        put("content", "- call mom\n")
                        put("append", true)
                    },
                ).success,
            )
            val edited =
                run(
                    "workspace_edit",
                    args {
                        put("path", "notes/todo.md")
                        put("oldText", "milk")
                        put("newText", "oat milk")
                    },
                )
            val read = run("workspace_read", args { put("path", "notes/todo.md") })

            assertTrue(edited.success)
            assertEquals(
                "- buy oat milk\n- call mom\n",
                read.payload
                    .getValue("text")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `workspace rejects traversal and ambiguous edits`() =
        runTest {
            run(
                "workspace_write",
                args {
                    put("path", "a.md")
                    put("content", "x x")
                },
            )

            assertEquals("INVALID_PATH", run("workspace_read", args { put("path", "../secrets") }).errorCode)
            assertEquals(
                "INVALID_PATH",
                run(
                    "workspace_write",
                    args {
                        put("path", "notes/../../x")
                        put("content", "y")
                    },
                ).errorCode,
            )
            assertEquals(
                "TEXT_NOT_UNIQUE",
                run(
                    "workspace_edit",
                    args {
                        put("path", "a.md")
                        put("oldText", "x")
                    },
                ).errorCode,
            )
            assertEquals(
                "TEXT_NOT_FOUND",
                run(
                    "workspace_edit",
                    args {
                        put("path", "a.md")
                        put("oldText", "zzz")
                    },
                ).errorCode,
            )
            assertEquals("FILE_NOT_FOUND", run("workspace_read", args { put("path", "missing.md") }).errorCode)
        }

    // --- web --------------------------------------------------------------------------------

    @Test
    fun `web fetch converts html to readable text`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody(
                            "<html><head><title>Weather &amp; More</title><style>p{}</style></head>" +
                                "<body><script>alert(1)</script><h1>Today</h1><p>Sunny, 24&deg;C</p><ul><li>Wind 5 km/h</li></ul></body></html>",
                        ),
                )
                server.start()

                val result = run("web_fetch", args { put("url", server.url("/today").toString()) }, allowPrivateNetwork = true)

                assertTrue(result.summary, result.success)
                assertTrue(result.summary.contains("Weather & More"))
                val text =
                    result.payload
                        .getValue("text")
                        .jsonPrimitive.content
                assertTrue(text, text.contains("Today\n"))
                assertTrue(text.contains("- Wind 5 km/h"))
                assertFalse(text.contains("alert"))
                assertFalse(text.contains("<p>"))
            }
        }

    @Test
    fun `web fetch reports http errors binary content and bad urls`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(404).setBody("not here"))
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("PNG"))
                server.start()

                val notFound = run("web_fetch", args { put("url", server.url("/a").toString()) }, allowPrivateNetwork = true)
                val binary = run("web_fetch", args { put("url", server.url("/b").toString()) }, allowPrivateNetwork = true)

                assertEquals("HTTP_ERROR", notFound.errorCode)
                assertEquals("UNSUPPORTED_CONTENT", binary.errorCode)
            }
            assertEquals("INVALID_URL", run("web_fetch", args { put("url", "ftp://example.com/file") }).errorCode)
        }

    @Test
    fun `web fetch blocks local network addresses by default`() =
        runTest {
            val literal = run("web_fetch", args { put("url", "http://127.0.0.1:9/") })
            val viaServer =
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setBody("local secret"))
                    server.start()
                    run("web_fetch", args { put("url", server.url("/").toString()) })
                }

            assertEquals("FETCH_BLOCKED_OR_UNKNOWN_HOST", literal.errorCode)
            assertTrue(literal.summary.contains("private network"))
            assertEquals(viaServer.summary, "FETCH_BLOCKED_OR_UNKNOWN_HOST", viaServer.errorCode)
        }

    @Test
    fun `html helpers decode entities and drop noise`() {
        assertEquals("a & b < c ©", decodeHtmlEntities("a &amp; b &lt; c &#169;"))
        assertEquals("Title", htmlTitle("<html><title> Title </title></html>"))
        assertEquals("Hello\n\nWorld", htmlToText("<p>Hello</p>\n\n\n\n<p>World</p><!-- hidden -->"))
    }

    // --- device -----------------------------------------------------------------------------

    @Test
    fun `device status reports time zone and is available to scheduled runs`() =
        runTest {
            val result = run("device_status", origin = ToolInvocationOrigin.ScheduledModel, runMode = ModelRunMode.Scheduled)

            assertTrue(result.summary, result.success)
            assertTrue(result.summary.contains("Local time 2026-03-08 00:00, Sunday (Z, UTC+00:00)."))
            assertTrue(result.summary.contains("Network: Connected."))
            assertEquals(
                "Z",
                result.payload
                    .getValue("timezone")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `open url launches a view intent and rejects unsupported schemes`() =
        runTest {
            val opened = run("open_url", args { put("url", "https://example.com/a") })
            val started = shadowOf(application).nextStartedActivity
            val rejected = run("open_url", args { put("url", "file:///sdcard/x") })

            assertTrue(opened.success)
            assertEquals(Intent.ACTION_VIEW, started.action)
            assertEquals("https://example.com/a", started.dataString)
            assertEquals("INVALID_URL", rejected.errorCode)
        }

    @Test
    fun `alarm timer settings and calendar build system intents`() =
        runTest {
            assertTrue(
                run(
                    "set_alarm",
                    args {
                        put("hour", 7)
                        put("minute", 15)
                        put("label", "Gym")
                        put("days", "mon,wed")
                    },
                ).success,
            )
            val alarm = shadowOf(application).nextStartedActivity
            assertTrue(run("set_timer", args { put("seconds", 90) }).success)
            val timer = shadowOf(application).nextStartedActivity
            assertTrue(run("open_settings", args { put("panel", "wifi") }).success)
            val settings = shadowOf(application).nextStartedActivity
            val event =
                run(
                    "calendar_add_event",
                    args {
                        put("title", "Dentist")
                        put("startIso", "2026-03-10T15:00")
                    },
                )
            val calendar = shadowOf(application).nextStartedActivity

            assertEquals(AlarmClock.ACTION_SET_ALARM, alarm.action)
            assertEquals(7, alarm.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
            assertEquals(15, alarm.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
            assertEquals(listOf(java.util.Calendar.MONDAY, java.util.Calendar.WEDNESDAY), alarm.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))
            assertEquals(AlarmClock.ACTION_SET_TIMER, timer.action)
            assertEquals(90, timer.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
            assertEquals(Settings.ACTION_WIFI_SETTINGS, settings.action)
            assertTrue(event.success)
            assertEquals(Intent.ACTION_INSERT, calendar.action)
            assertEquals(Instant.parse("2026-03-10T15:00:00Z").toEpochMilli(), calendar.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
            assertEquals(Instant.parse("2026-03-10T16:00:00Z").toEpochMilli(), calendar.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0))
            assertEquals("INVALID_ARGUMENTS", run("set_timer", args { put("seconds", 0) }).errorCode)
            assertEquals("INVALID_ARGUMENTS", run("open_settings", args { put("panel", "nowhere") }).errorCode)
        }

    @Test
    fun `foreground only tools fail cleanly in the background and in scheduled runs`() =
        runTest {
            val scheduled =
                run(
                    "open_url",
                    args { put("url", "https://example.com") },
                    origin = ToolInvocationOrigin.ScheduledModel,
                    runMode = ModelRunMode.Scheduled,
                )
            foreground = false
            val background = run("clipboard_get")

            assertEquals("FOREGROUND_REQUIRED", scheduled.errorCode)
            assertEquals("FOREGROUND_REQUIRED", background.errorCode)
            assertNull(shadowOf(application).nextStartedActivity)
        }

    @Test
    fun `clipboard round trip and unknown app`() =
        runTest {
            assertTrue(run("clipboard_set", args { put("text", "copied text") }).success)
            val read = run("clipboard_get")
            val clip = application.getSystemService(ClipboardManager::class.java).primaryClip

            assertEquals("copied text", clip?.getItemAt(0)?.text?.toString())
            assertEquals(
                "copied text",
                read.payload
                    .getValue("text")
                    .jsonPrimitive.content,
            )
            assertEquals("APP_NOT_FOUND", run("open_app", args { put("app", "Definitely Missing App") }).errorCode)
        }

    @Test
    fun `notify needs notification permission and then posts`() =
        runTest {
            val denied = run("notify", args { put("title", "Hi") })
            shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val posted =
                run(
                    "notify",
                    args {
                        put("title", "Hi")
                        put("body", "Body")
                    },
                )

            assertEquals("PERMISSION_REQUIRED", denied.errorCode)
            assertTrue(posted.summary, posted.success)
        }

    // --- health and slash helpers -----------------------------------------------------------

    @Test
    fun `health status summarizes runtime state`() =
        runTest {
            val result = run("health_status")

            assertTrue(result.summary.contains("Provider: Fake (offline)."))
            assertTrue(result.summary.contains("Memory: off."))
            assertTrue(result.summary.contains("Tools: "))
        }

    @Test
    fun `memory slash command shows content and guards clear`() =
        runTest {
            settingsDataStore.setMemoryEnabled(true)
            run("memory_command", args { put("command", "remember User lives in Hangzhou") }, origin = ToolInvocationOrigin.SlashCommand)

            val list = run("memory_command", args { put("command", "list") }, origin = ToolInvocationOrigin.SlashCommand)
            val unguarded = run("memory_command", args { put("command", "clear") }, origin = ToolInvocationOrigin.SlashCommand)
            val fromModel = run("memory_command", args { put("command", "list") })

            assertTrue(list.summary.contains("User lives in Hangzhou"))
            assertEquals("CONFIRMATION_REQUIRED", unguarded.errorCode)
            assertEquals("TOOL_ORIGIN_NOT_ALLOWED", fromModel.errorCode)
        }

    // --- chat card headlines -----------------------------------------------------------------

    @Test
    fun `success headlines of the built in tools all have a localized card template`() =
        runTest {
            shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            settingsDataStore.setMemoryEnabled(true)
            skills = listOf(testSkillSnapshot(id = "bundled:weather", name = "weather"))
            val results = mutableListOf<Pair<String, ToolExecutionResult>>()

            suspend fun call(
                name: String,
                arguments: JsonObject = buildJsonObject {},
                sessionId: String? = null,
                origin: ToolInvocationOrigin = ToolInvocationOrigin.Model,
            ): ToolExecutionResult = run(name, arguments, sessionId = sessionId, origin = origin).also { results += name to it }

            suspend fun memoryCommand(command: String) = call("memory_command", args { put("command", command) }, origin = ToolInvocationOrigin.SlashCommand)

            call("device_status")
            call("health_status")
            call("tasks_list")
            call("tasks_runs")
            val taskId =
                call(
                    "tasks_create",
                    args {
                        put("name", "Hourly check")
                        put("prompt", "Check things")
                        put("repeatEveryMinutes", 60)
                    },
                ).payload["task"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.content
            call("tasks_list")
            call(
                "tasks_update",
                args {
                    put("taskId", taskId)
                    put("enabled", false)
                },
            )
            call("tasks_run_now", args { put("taskId", taskId) })
            call("tasks_delete", args { put("taskId", taskId) })

            call("sessions_list")
            val newId =
                call("sessions_create", args { put("title", "Trip planning") })
                    .payload
                    .getValue("id")
                    .jsonPrimitive.content
            call("sessions_rename", args { put("title", "Trip to Kyoto") }, sessionId = newId)
            call(
                "sessions_send",
                args {
                    put("sessionId", "main")
                    put("text", "Flights booked.")
                },
                sessionId = newId,
            )
            call("sessions_history", args { put("sessionId", "main") })

            call("skills_list")
            call(
                "skills_set_enabled",
                args {
                    put("name", "weather")
                    put("enabled", false)
                },
            )

            call("memory_search", args { put("query", "tea") })
            val memoryId = call("memory_remember", args { put("text", "User likes green tea") }).summary.substringAfter("id=").substringBefore(")")
            call("memory_search", args { put("query", "tea") })
            memoryCommand("delete $memoryId")
            memoryCommand("status")
            memoryCommand("remember User lives in Hangzhou")
            memoryCommand("list")
            val forgetId = call("memory_remember", args { put("text", "User likes jasmine tea") }).summary.substringAfter("id=").substringBefore(")")
            call("memory_forget", args { put("id", forgetId) })
            memoryCommand("clear CONFIRM")
            memoryCommand("list")

            call("workspace_list")
            call(
                "workspace_write",
                args {
                    put("path", "notes/todo.md")
                    put("content", "- buy milk\n")
                },
            )
            call(
                "workspace_write",
                args {
                    put("path", "notes/todo.md")
                    put("content", "- call mom\n")
                    put("append", true)
                },
            )
            call(
                "workspace_edit",
                args {
                    put("path", "notes/todo.md")
                    put("oldText", "milk")
                    put("newText", "oat milk")
                },
            )
            call("workspace_read", args { put("path", "notes/todo.md") })
            call(
                "workspace_read",
                args {
                    put("path", "notes/todo.md")
                    put("maxChars", 4)
                },
            )

            call("open_url", args { put("url", "https://example.com/a") })
            call("open_settings", args { put("panel", "wifi") })
            call(
                "set_alarm",
                args {
                    put("hour", 7)
                    put("minute", 15)
                    put("label", "Gym")
                },
            )
            call(
                "set_alarm",
                args {
                    put("hour", 7)
                    put("minute", 15)
                },
            )
            call("set_timer", args { put("seconds", 90) })
            call(
                "calendar_add_event",
                args {
                    put("title", "Dentist")
                    put("startIso", "2026-03-10T15:00")
                },
            )
            call("clipboard_get")
            call("clipboard_set", args { put("text", "copied text") })
            call("clipboard_get")
            call("notify", args { put("title", "Hi") })

            MockWebServer().use { server ->
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><head><title>Weather</title></head><body>Sunny</body></html>"))
                server.start()
                results += "web_fetch" to run("web_fetch", args { put("url", server.url("/today").toString()) }, allowPrivateNetwork = true)
            }

            results.forEach { (name, result) ->
                assertTrue("$name: ${result.summary}", result.success)
                val headline = parseToolResultView(result.toModelContent()).headline
                assertNotNull("$name: $headline", localizedToolHeadline(name, headline, Locale.SIMPLIFIED_CHINESE))
            }
        }

    // --- helpers ----------------------------------------------------------------------------

    private suspend fun createIntervalTask(): String {
        val created =
            run(
                "tasks_create",
                args {
                    put("name", "Hourly check")
                    put("prompt", "Check things")
                    put("repeatEveryMinutes", 60)
                },
            )
        assertTrue(created.summary, created.success)
        val id =
            created.payload["task"]
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.content
        assertNotNull(id)
        return id!!
    }

    private suspend fun run(
        name: String,
        arguments: JsonObject = buildJsonObject {},
        sessionId: String? = null,
        origin: ToolInvocationOrigin = ToolInvocationOrigin.Model,
        runMode: ModelRunMode = if (origin == ToolInvocationOrigin.ScheduledModel) ModelRunMode.Scheduled else ModelRunMode.Interactive,
        allowPrivateNetwork: Boolean = false,
    ): ToolExecutionResult =
        buildRegistry(allowPrivateNetwork).execute(
            context =
                ToolExecutionContext(
                    sessionId = sessionId ?: sessionRepository.getOrCreateMainSession().id,
                    taskRunId = null,
                    origin = origin,
                    runMode = runMode,
                    requestedName = name,
                    canonicalName = name,
                    requestId = "request-1",
                ),
            arguments = arguments,
        )

    private fun buildRegistry(allowPrivateNetwork: Boolean): ToolRegistry =
        createBuiltInToolRegistry(
            application = application,
            settingsDataStore = settingsDataStore,
            sessionRepository = sessionRepository,
            taskRepository = taskRepository,
            schedulerCoordinator = schedulerCoordinator,
            bundledSkillsProvider = { skills },
            skillEnabledUpdater = { id, enabled -> skillToggles += id to enabled },
            messageRepository = messageRepository,
            memoryRepository = memoryRepository,
            workspaceStore = workspaceStore,
            webFetchAllowPrivateNetwork = allowPrivateNetwork,
            networkStatusProvider =
                object : NetworkStatusProvider {
                    override fun currentStatus(): NetworkStatusSnapshot = NetworkStatusSnapshot(supported = true, isConnected = true, isValidated = true, isMetered = false)
                },
            isAppInForeground = { foreground },
            clock = testClock,
        )
}

private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)
