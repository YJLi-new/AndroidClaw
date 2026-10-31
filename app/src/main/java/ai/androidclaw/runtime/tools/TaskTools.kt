package ai.androidclaw.runtime.tools

import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock

private const val TASKS_LIST_MAX_ITEMS = 50
private const val TASK_RUNS_DEFAULT_LIMIT = 10
private const val TASK_RUNS_MAX_LIMIT = 30

private val SCHEDULE_ARGUMENTS =
    listOf(
        stringArg("scheduleKind", "once, interval or cron.", enumValues = listOf("once", "interval", "cron")),
        stringArg("atIso", "once: local or ISO date-time, e.g. 2026-03-08T09:30."),
        intArg("repeatEveryMinutes", "interval: minutes between runs (min 15)."),
        stringArg("anchorAtIso", "interval: first run time. Default now."),
        stringArg("cronExpression", "cron: 5 fields 'min hour dom month dow'."),
        stringArg("timezone", "cron: IANA zone. Default device zone."),
    )

private val TASK_TARGET_ARGUMENTS =
    listOf(
        stringArg(
            "executionMode",
            "MAIN_SESSION posts into a session; ISOLATED_SESSION runs in a fresh one.",
            enumValues = listOf("MAIN_SESSION", "ISOLATED_SESSION"),
        ),
        stringArg("targetSessionId", "Session for MAIN_SESSION runs: id, 'current' or 'main'."),
        boolArg("precise", "Use an exact alarm when allowed."),
    )

private val TASK_ID_ARGUMENT = stringArg("taskId", "Task id from tasks_list.", required = true)

internal fun taskToolEntries(
    taskRepository: TaskRepository,
    sessionRepository: SessionRepository,
    schedulerCoordinator: SchedulerCoordinator,
    clock: Clock,
): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks_list",
                    aliases = listOf("tasks.list", "task.list"),
                    description = "List scheduled tasks (automations) with id, schedule, state and next run.",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                ),
        ) { _, _ ->
            val tasks = taskRepository.observeTasks().first()
            if (tasks.isEmpty()) {
                return@Entry toolSuccess(
                    "No scheduled tasks yet. Create one with tasks_create (once, interval of 15+ min, or cron).",
                )
            }
            toolSuccess(
                summary =
                    buildString {
                        append("${tasks.size} task(s):")
                        tasks.take(TASKS_LIST_MAX_ITEMS).forEach { task -> append('\n').append(task.describeLine(clock.zone)) }
                        if (tasks.size > TASKS_LIST_MAX_ITEMS) append("\n… ${tasks.size - TASKS_LIST_MAX_ITEMS} more")
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks_create",
                    aliases = listOf("tasks.create", "cron.add"),
                    description =
                        "Schedule a prompt to run later: once, every N minutes, or by cron. " +
                            "Times without an offset use the device timezone.",
                    riskTier = ToolRiskTier.WritesLocalState,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    arguments =
                        listOf(
                            stringArg("name", "Short task name.", required = true),
                            stringArg("prompt", "What the assistant should do when it runs.", required = true),
                        ) + SCHEDULE_ARGUMENTS + TASK_TARGET_ARGUMENTS,
                ),
        ) { context, arguments ->
            val now = clock.instant()
            val spec =
                parseTaskCreateSpec(
                    arguments = arguments,
                    context = context,
                    sessionRepository = sessionRepository,
                    capabilities = schedulerCoordinator.capabilities(),
                    now = now,
                    zoneId = clock.zone,
                )
            val created =
                taskRepository.createTask(
                    name = spec.name,
                    prompt = spec.prompt,
                    schedule = spec.schedule,
                    executionMode = spec.executionMode,
                    targetSessionId = spec.targetSessionId,
                    precise = spec.precise,
                    maxRetries = spec.maxRetries,
                )
            schedulerCoordinator.scheduleTask(created.id)
            val task = taskRepository.getTask(created.id) ?: created
            toolSuccess(
                summary = "Created task:\n${task.describeLine(clock.zone)}",
                payload = buildJsonObject { put("task", task.toCompactPayload()) },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks_update",
                    aliases = listOf("tasks.update", "tasks.enable", "tasks.disable"),
                    description = "Change a task: any create field, or enabled=true/false to pause or resume it.",
                    riskTier = ToolRiskTier.WritesLocalState,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    arguments =
                        listOf(
                            TASK_ID_ARGUMENT,
                            stringArg("name", "New name."),
                            stringArg("prompt", "New prompt."),
                            boolArg("enabled", "false pauses, true resumes."),
                        ) + SCHEDULE_ARGUMENTS + TASK_TARGET_ARGUMENTS,
                ),
        ) { context, arguments ->
            val taskId = arguments.requireText("taskId", "tasks_update")
            val existing = taskRepository.getTask(taskId) ?: return@Entry taskNotFound(taskId)
            val forcedEnabled =
                when (context.requestedName) {
                    "tasks.enable" -> true
                    "tasks.disable" -> false
                    else -> null
                }
            val parsed =
                parseTaskUpdate(
                    existingTask = existing,
                    arguments = arguments,
                    context = context,
                    sessionRepository = sessionRepository,
                    capabilities = schedulerCoordinator.capabilities(),
                    now = clock.instant(),
                    zoneId = clock.zone,
                )
            val updated =
                (forcedEnabled?.let { enabled -> parsed.copy(enabled = enabled) } ?: parsed)
                    .withResumedNextRun(previouslyEnabled = existing.enabled, clock = clock)
            taskRepository.updateTask(updated)
            if (updated.enabled) {
                schedulerCoordinator.scheduleTask(updated.id)
            } else {
                schedulerCoordinator.cancelTask(updated.id)
            }
            val task = taskRepository.getTask(updated.id) ?: updated
            toolSuccess(
                summary = "Updated task:\n${task.describeLine(clock.zone)}",
                payload = buildJsonObject { put("task", task.toCompactPayload()) },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks_delete",
                    aliases = listOf("tasks.delete", "cron.remove"),
                    description = "Delete a task and cancel its future runs.",
                    riskTier = ToolRiskTier.Destructive,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    arguments = listOf(TASK_ID_ARGUMENT),
                ),
        ) { _, arguments ->
            val taskId = arguments.requireText("taskId", "tasks_delete")
            val task = taskRepository.getTask(taskId) ?: return@Entry taskNotFound(taskId)
            schedulerCoordinator.cancelTask(task.id)
            taskRepository.deleteTask(task.id)
            toolSuccess(summary = "Deleted task ${task.name}.", payload = buildJsonObject { put("taskId", task.id) })
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks_run_now",
                    aliases = listOf("tasks.run_now", "cron.run"),
                    description = "Run a task once right away in the background; its schedule is unchanged.",
                    riskTier = ToolRiskTier.WritesLocalState,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    arguments = listOf(TASK_ID_ARGUMENT),
                ),
        ) { _, arguments ->
            val taskId = arguments.requireText("taskId", "tasks_run_now")
            val task = taskRepository.getTask(taskId) ?: return@Entry taskNotFound(taskId)
            schedulerCoordinator.runNow(task.id)
            toolSuccess(
                summary = "Queued ${task.name} to run now. Check tasks_runs for the result.",
                payload = buildJsonObject { put("taskId", task.id) },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks_runs",
                    aliases = listOf("tasks.runs", "cron.runs"),
                    description = "Show recent runs of one task, or of all tasks when taskId is omitted.",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                    arguments =
                        listOf(
                            stringArg("taskId", "Task id. Omit for all tasks."),
                            intArg("limit", "Max runs, default 10, max 30."),
                        ),
                ),
        ) { _, arguments ->
            val limit = arguments.boundedInt("limit", TASK_RUNS_DEFAULT_LIMIT, 1, TASK_RUNS_MAX_LIMIT)
            val taskId = arguments.optionalText("taskId")
            val runs =
                if (taskId != null) {
                    taskRepository.getTask(taskId) ?: return@Entry taskNotFound(taskId)
                    taskRepository.getRecentRuns(taskId, limit)
                } else {
                    taskRepository.getRecentRuns(limit)
                }
            if (runs.isEmpty()) {
                return@Entry toolSuccess("No task runs recorded yet.")
            }
            val names = runs.map { it.taskId }.distinct().associateWith { id -> taskRepository.getTask(id)?.name ?: id }
            toolSuccess(
                summary =
                    buildString {
                        append("${runs.size} run(s), newest first:")
                        runs.forEach { run ->
                            append('\n').append(run.describeLine(clock.zone))
                            if (taskId == null) append(" [").append(names[run.taskId]).append(']')
                        }
                    },
            )
        },
    )

private fun ai.androidclaw.data.model.Task.withResumedNextRun(
    previouslyEnabled: Boolean,
    clock: Clock,
): ai.androidclaw.data.model.Task =
    if (enabled && !previouslyEnabled && (nextRunAt == null || nextRunAt.isBefore(clock.instant()))) {
        copy(nextRunAt = taskNextRun(schedule, clock.instant()))
    } else {
        this
    }

private fun taskNotFound(taskId: String): ToolExecutionResult =
    toolFailure(
        errorCode = "TASK_NOT_FOUND",
        summary = "Task $taskId was not found. Use tasks_list to see task ids.",
    )
