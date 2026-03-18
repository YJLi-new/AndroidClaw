package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.skills.SkillSnapshot
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

internal fun createBuiltInToolRegistry(
    application: Application,
    settingsDataStore: SettingsDataStore,
    sessionRepository: SessionRepository,
    taskRepository: TaskRepository,
    schedulerCoordinator: SchedulerCoordinator,
    bundledSkillsProvider: suspend () -> List<SkillSnapshot>,
    eventLogRepository: EventLogRepository? = null,
): ToolRegistry {
    lateinit var toolRegistry: ToolRegistry
    toolRegistry =
        ToolRegistry(
            eventLogger = { level, message, details ->
                eventLogRepository?.log(
                    category = EventCategory.Tool,
                    level = level,
                    message = message,
                    details = details,
                )
            },
            tools =
                buildList {
                    addAll(
                        taskToolEntries(
                            taskRepository = taskRepository,
                            sessionRepository = sessionRepository,
                            schedulerCoordinator = schedulerCoordinator,
                        ),
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "health.status",
                                    aliases = listOf("health.check"),
                                    description = "Return lightweight runtime health information and tool availability.",
                                ),
                        ) { _, _ ->
                            val providerType = settingsDataStore.settings.first().providerType
                            ToolExecutionResult.success(
                                summary = "Runtime bootstrapped with ${providerType.displayName}, bundled skills, and scheduler preview support.",
                                payload =
                                    buildJsonObject {
                                        put("provider", providerType.providerId)
                                        put("schedulerReady", true)
                                        put("skillsReady", true)
                                        put(
                                            "tools",
                                            buildJsonArray {
                                                toolRegistry.descriptors().forEach { tool ->
                                                    add(
                                                        buildJsonObject {
                                                            put("name", tool.name)
                                                            put(
                                                                "aliases",
                                                                buildJsonArray {
                                                                    tool.aliases.forEach { add(JsonPrimitive(it)) }
                                                                },
                                                            )
                                                            put("availabilityStatus", tool.availability.status.name)
                                                            put("foregroundRequired", tool.foregroundRequired)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.list",
                                    aliases = listOf("session.list"),
                                    description = "List known chat sessions.",
                                ),
                        ) { _, _ ->
                            val sessions = sessionRepository.observeSessions().first()
                            ToolExecutionResult.success(
                                summary =
                                    if (sessions.isEmpty()) {
                                        "No sessions found."
                                    } else {
                                        "Found ${sessions.size} session(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("sessionCount", sessions.size)
                                        put(
                                            "sessions",
                                            buildJsonArray {
                                                sessions.forEach { session ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", session.id)
                                                            put("title", session.title)
                                                            put("isMain", session.isMain)
                                                            put("archived", session.archived)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "skills.list",
                                    aliases = listOf("skill.list"),
                                    description = "List bundled skills and their current eligibility.",
                                ),
                        ) { _, _ ->
                            val skills = bundledSkillsProvider()
                            ToolExecutionResult.success(
                                summary =
                                    if (skills.isEmpty()) {
                                        "No bundled skills found."
                                    } else {
                                        "Found ${skills.size} bundled skill(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("skillCount", skills.size)
                                        put(
                                            "skills",
                                            buildJsonArray {
                                                skills.forEach { skill ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", skill.id)
                                                            put("name", skill.displayName)
                                                            put("enabled", skill.enabled)
                                                            put("sourceType", skill.sourceType.name)
                                                            put("eligibilityStatus", skill.eligibility.status.name)
                                                            put(
                                                                "eligibilityReasons",
                                                                buildJsonArray {
                                                                    skill.eligibility.reasons.forEach { add(JsonPrimitive(it)) }
                                                                },
                                                            )
                                                            put(
                                                                "secretStatuses",
                                                                buildJsonArray {
                                                                    skill.secretStatuses.forEach { (envName, configured) ->
                                                                        add(
                                                                            buildJsonObject {
                                                                                put("envName", envName)
                                                                                put("configured", configured)
                                                                            },
                                                                        )
                                                                    }
                                                                },
                                                            )
                                                            put(
                                                                "configStatuses",
                                                                buildJsonArray {
                                                                    skill.configStatuses.forEach { (path, configured) ->
                                                                        add(
                                                                            buildJsonObject {
                                                                                put("path", path)
                                                                                put("configured", configured)
                                                                            },
                                                                        )
                                                                    }
                                                                },
                                                            )
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "notifications.post",
                                    aliases = listOf("notification.post"),
                                    description = "Post a lightweight Android notification.",
                                    requiredPermissions =
                                        listOf(
                                            ToolPermissionRequirement(
                                                permission = android.Manifest.permission.POST_NOTIFICATIONS,
                                                displayName = "Post notifications",
                                            ),
                                        ),
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "title",
                                                required = true,
                                                description = "Notification title",
                                            ),
                                            ToolArgumentSpec(
                                                name = "body",
                                                description = "Notification body",
                                            ),
                                        ),
                                ),
                            availabilityProvider = { notificationToolAvailability(application) },
                        ) { _, arguments ->
                            val title = arguments["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val body = arguments["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val notificationManager = NotificationManagerCompat.from(application)
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    application,
                                    android.Manifest.permission.POST_NOTIFICATIONS,
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Grant notification permission to use notifications.post.",
                                    errorCode = "PERMISSION_REQUIRED",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "PERMISSION_REQUIRED")
                                            put("toolName", "notifications.post")
                                        },
                                )
                            }
                            if (!notificationManager.areNotificationsEnabled()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Enable app notifications to use notifications.post.",
                                    errorCode = "TOOL_UNAVAILABLE",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "TOOL_UNAVAILABLE")
                                            put("toolName", "notifications.post")
                                        },
                                )
                            }
                            ensureToolNotificationChannel(application)
                            val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                            notificationManager.notify(
                                notificationId,
                                NotificationCompat
                                    .Builder(application, TOOL_NOTIFICATION_CHANNEL_ID)
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setContentTitle(title)
                                    .setContentText(body)
                                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                    .setAutoCancel(true)
                                    .build(),
                            )
                            ToolExecutionResult.success(
                                summary = "Posted notification \"$title\".",
                                payload =
                                    buildJsonObject {
                                        put("notificationId", notificationId)
                                        put("title", title)
                                        put("body", body)
                                    },
                            )
                        },
                    )
                },
        )
    return toolRegistry
}

// These handlers are the typed automation contract for v5. They intentionally mirror the
// repository's real schedule model instead of inventing a second scheduler abstraction.
private fun taskToolEntries(
    taskRepository: TaskRepository,
    sessionRepository: SessionRepository,
    schedulerCoordinator: SchedulerCoordinator,
): List<ToolRegistry.Entry> {
    return listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.list",
                    aliases = listOf("task.list"),
                    description = "List known automation capabilities and persisted tasks.",
                ),
        ) { _, _ ->
            val diagnostics = schedulerCoordinator.diagnostics()
            val tasks = taskRepository.observeTasks().first()
            ToolExecutionResult.success(
                summary =
                    if (tasks.isEmpty()) {
                        "No persisted tasks yet. Scheduler supports once, interval, and cron execution."
                    } else {
                        "Found ${tasks.size} persisted task(s)."
                    },
                payload =
                    buildJsonObject {
                        put("supportsOnce", true)
                        put("supportsInterval", true)
                        put("supportsCron", true)
                        put(
                            "minimumBackgroundIntervalMinutes",
                            schedulerCoordinator.capabilities().minimumBackgroundInterval.toMinutes(),
                        )
                        put("taskCount", tasks.size)
                        put(
                            "tasks",
                            buildJsonArray {
                                tasks.forEach { task ->
                                    add(
                                        buildTaskPayload(
                                            task = task,
                                            latestRun = taskRepository.getLatestRun(task.id),
                                            sessionRepository = sessionRepository,
                                            diagnostics = diagnostics,
                                        ),
                                    )
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.get",
                    description = "Return a canonical task payload and its latest run summary.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "taskId",
                                required = true,
                                description = "Task identifier",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.get",
                    summary = "tasks.get requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.get", taskId = taskId)
            ToolExecutionResult.success(
                summary = "Loaded task ${task.name}.",
                payload =
                    buildJsonObject {
                        put(
                            "task",
                            buildTaskPayload(
                                task = task,
                                latestRun = taskRepository.getLatestRun(task.id),
                                sessionRepository = sessionRepository,
                                diagnostics = schedulerCoordinator.diagnostics(),
                            ),
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor = taskCreateDescriptor(),
        ) { context, arguments ->
            val spec =
                try {
                    parseTaskCreateSpec(
                        arguments = arguments,
                        context = context,
                        sessionRepository = sessionRepository,
                        capabilities = schedulerCoordinator.capabilities(),
                        now = Instant.now(),
                    )
                } catch (error: IllegalArgumentException) {
                    return@Entry invalidTaskArguments(
                        toolName = "tasks.create",
                        summary = error.message ?: "tasks.create received invalid arguments.",
                    )
                }
            when (spec) {
                is TaskToolParseResult.Failure -> spec.result
                is TaskToolParseResult.Success -> {
                    val createdTask =
                        taskRepository.createTask(
                            name = spec.value.name,
                            prompt = spec.value.prompt,
                            schedule = spec.value.schedule,
                            executionMode = spec.value.executionMode,
                            targetSessionId = spec.value.targetSessionId,
                            precise = spec.value.precise,
                            maxRetries = spec.value.maxRetries,
                        )
                    schedulerCoordinator.scheduleTask(createdTask.id)
                    val reloadedTask = taskRepository.getTask(createdTask.id) ?: createdTask
                    ToolExecutionResult.success(
                        summary = "Created task ${reloadedTask.name}.",
                        payload =
                            buildJsonObject {
                                put(
                                    "task",
                                    buildTaskPayload(
                                        task = reloadedTask,
                                        latestRun = taskRepository.getLatestRun(reloadedTask.id),
                                        sessionRepository = sessionRepository,
                                        diagnostics = schedulerCoordinator.diagnostics(),
                                    ),
                                )
                            },
                    )
                }
            }
        },
        ToolRegistry.Entry(
            descriptor = taskUpdateDescriptor(),
        ) { context, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.update",
                    summary = "tasks.update requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val existingTask =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.update", taskId = taskId)
            val updatedTask =
                try {
                    parseTaskUpdate(
                        existingTask = existingTask,
                        arguments = arguments,
                        context = context,
                        sessionRepository = sessionRepository,
                        capabilities = schedulerCoordinator.capabilities(),
                        now = Instant.now(),
                    )
                } catch (error: IllegalArgumentException) {
                    return@Entry invalidTaskArguments(
                        toolName = "tasks.update",
                        summary = error.message ?: "tasks.update received invalid arguments.",
                    )
                }
            when (updatedTask) {
                is TaskToolParseResult.Failure -> updatedTask.result
                is TaskToolParseResult.Success -> {
                    taskRepository.updateTask(updatedTask.value)
                    if (updatedTask.value.enabled) {
                        schedulerCoordinator.scheduleTask(updatedTask.value.id)
                    } else {
                        schedulerCoordinator.cancelTask(updatedTask.value.id)
                    }
                    val reloadedTask = taskRepository.getTask(updatedTask.value.id) ?: updatedTask.value
                    ToolExecutionResult.success(
                        summary = "Updated task ${reloadedTask.name}.",
                        payload =
                            buildJsonObject {
                                put(
                                    "task",
                                    buildTaskPayload(
                                        task = reloadedTask,
                                        latestRun = taskRepository.getLatestRun(reloadedTask.id),
                                        sessionRepository = sessionRepository,
                                        diagnostics = schedulerCoordinator.diagnostics(),
                                    ),
                                )
                            },
                    )
                }
            }
        },
        ToolRegistry.Entry(
            descriptor =
                taskToggleDescriptor(
                    name = "tasks.enable",
                    description = "Enable a task and reschedule its next work.",
                ),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.enable",
                    summary = "tasks.enable requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.enable", taskId = taskId)
            val updatedTask =
                task.copy(
                    enabled = true,
                    updatedAt = Instant.now(),
                )
            taskRepository.updateTask(updatedTask)
            schedulerCoordinator.scheduleTask(updatedTask.id)
            val reloadedTask = taskRepository.getTask(updatedTask.id) ?: updatedTask
            ToolExecutionResult.success(
                summary = "Enabled task ${reloadedTask.name}.",
                payload =
                    buildJsonObject {
                        put(
                            "task",
                            buildTaskPayload(
                                task = reloadedTask,
                                latestRun = taskRepository.getLatestRun(reloadedTask.id),
                                sessionRepository = sessionRepository,
                                diagnostics = schedulerCoordinator.diagnostics(),
                            ),
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                taskToggleDescriptor(
                    name = "tasks.disable",
                    description = "Disable a task and cancel its queued work.",
                ),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.disable",
                    summary = "tasks.disable requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.disable", taskId = taskId)
            val updatedTask =
                task.copy(
                    enabled = false,
                    updatedAt = Instant.now(),
                )
            taskRepository.updateTask(updatedTask)
            schedulerCoordinator.cancelTask(updatedTask.id)
            val reloadedTask = taskRepository.getTask(updatedTask.id) ?: updatedTask
            ToolExecutionResult.success(
                summary = "Disabled task ${reloadedTask.name}.",
                payload =
                    buildJsonObject {
                        put(
                            "task",
                            buildTaskPayload(
                                task = reloadedTask,
                                latestRun = taskRepository.getLatestRun(reloadedTask.id),
                                sessionRepository = sessionRepository,
                                diagnostics = schedulerCoordinator.diagnostics(),
                            ),
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                taskToggleDescriptor(
                    name = "tasks.delete",
                    description = "Delete a task and cancel any future work.",
                ),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.delete",
                    summary = "tasks.delete requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.delete", taskId = taskId)
            schedulerCoordinator.cancelTask(task.id)
            taskRepository.deleteTask(task.id)
            ToolExecutionResult.success(
                summary = "Deleted task ${task.name}.",
                payload =
                    buildJsonObject {
                        put("deletedTaskId", task.id)
                        put("deletedTaskName", task.name)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                taskToggleDescriptor(
                    name = "tasks.run_now",
                    description = "Queue immediate execution without changing the future schedule.",
                ),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.run_now",
                    summary = "tasks.run_now requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.run_now", taskId = taskId)
            val queuedAt = Instant.now()
            schedulerCoordinator.runNow(task.id)
            val reloadedTask = taskRepository.getTask(task.id) ?: task
            ToolExecutionResult.success(
                summary = "Queued run now for ${task.name}.",
                payload =
                    buildJsonObject {
                        put("queuedAtIso", queuedAt.toString())
                        put("trigger", "manual")
                        put(
                            "task",
                            buildTaskPayload(
                                task = reloadedTask,
                                latestRun = taskRepository.getLatestRun(reloadedTask.id),
                                sessionRepository = sessionRepository,
                                diagnostics = schedulerCoordinator.diagnostics(),
                            ),
                        )
                    },
            )
        },
    )
}

private fun taskCreateDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.create",
        description = "Create a scheduled automation using explicit schedule fields.",
        arguments = taskMutationArguments(requiredTaskId = false),
    )

private fun taskUpdateDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.update",
        description = "Patch an existing task without replacing unspecified fields.",
        arguments = taskMutationArguments(requiredTaskId = true),
    )

private fun taskToggleDescriptor(
    name: String,
    description: String,
): ToolDescriptor =
    ToolDescriptor(
        name = name,
        description = description,
        arguments =
            listOf(
                ToolArgumentSpec(
                    name = "taskId",
                    required = true,
                    description = "Task identifier",
                ),
            ),
    )

private fun taskMutationArguments(requiredTaskId: Boolean): List<ToolArgumentSpec> =
    buildList {
        if (requiredTaskId) {
            add(
                ToolArgumentSpec(
                    name = "taskId",
                    required = true,
                    description = "Task identifier",
                ),
            )
        }
        add(
            ToolArgumentSpec(
                name = "name",
                required = !requiredTaskId,
                description = "Task name",
            ),
        )
        add(
            ToolArgumentSpec(
                name = "prompt",
                required = !requiredTaskId,
                description = "Prompt sent when the task runs",
            ),
        )
        add(
            ToolArgumentSpec(
                name = "scheduleKind",
                required = !requiredTaskId,
                description = "once | interval | cron",
            ),
        )
        add(ToolArgumentSpec(name = "atIso", description = "ISO-8601 timestamp for once schedules"))
        add(ToolArgumentSpec(name = "anchorAtIso", description = "ISO-8601 anchor for interval schedules"))
        add(ToolArgumentSpec(name = "repeatEveryMinutes", description = "Interval cadence in minutes"))
        add(ToolArgumentSpec(name = "cronExpression", description = "Cron expression for cron schedules"))
        add(ToolArgumentSpec(name = "timezone", description = "ZoneId for cron schedules"))
        add(ToolArgumentSpec(name = "executionMode", description = "MAIN_SESSION | ISOLATED_SESSION"))
        add(ToolArgumentSpec(name = "targetSessionId", description = "Persisted target session id"))
        add(ToolArgumentSpec(name = "targetSessionAlias", description = "main | current"))
        add(ToolArgumentSpec(name = "precise", description = "true | false"))
        add(ToolArgumentSpec(name = "maxRetries", description = "Non-negative retry count"))
    }

private const val TOOL_NOTIFICATION_CHANNEL_ID = "androidclaw.tools"

internal fun notificationToolAvailability(application: Application): ToolAvailability {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            application,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return ToolAvailability(
            status = ToolAvailabilityStatus.PermissionRequired,
            reason = "Grant notification permission to use notifications.post.",
        )
    }
    if (!NotificationManagerCompat.from(application).areNotificationsEnabled()) {
        return ToolAvailability(
            status = ToolAvailabilityStatus.Unavailable,
            reason = "Enable app notifications to use notifications.post.",
        )
    }
    return ToolAvailability()
}

private fun ensureToolNotificationChannel(application: Application) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return
    }
    val notificationManager = application.getSystemService(NotificationManager::class.java)
    val channel =
        NotificationChannel(
            TOOL_NOTIFICATION_CHANNEL_ID,
            "AndroidClaw tools",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications created by AndroidClaw tool executions."
        }
    notificationManager.createNotificationChannel(channel)
}
