package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException

internal fun taskRunToolEntries(
    taskRepository: TaskRepository,
    sessionRepository: SessionRepository,
    schedulerCoordinator: SchedulerCoordinator,
    clock: Clock,
): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.runs",
                    aliases = listOf("task.runs", "tasks.history", "task.history"),
                    description = "Return recent run history for a scheduled automation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "taskId",
                                required = true,
                                description = "Task identifier",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum run count. Defaults to 10.",
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
                    toolName = "tasks.runs",
                    summary = "tasks.runs requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.runs", taskId = taskId)
            val limit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = TASK_RUN_HISTORY_DEFAULT_LIMIT,
                )
            val runs = taskRepository.getRecentRuns(taskId = task.id, limit = limit)
            ToolExecutionResult.success(
                summary = "Loaded ${runs.size} recent run(s) for task ${task.name}.",
                payload =
                    buildJsonObject {
                        put("taskId", task.id)
                        put("taskName", task.name)
                        put("returnedCount", runs.size)
                        put("recentFirst", true)
                        put(
                            "runs",
                            buildJsonArray {
                                runs.forEach { run ->
                                    add(run.toTaskRunHistoryPayload())
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.run.get",
                    aliases = listOf("task.run.get", "taskrun.get"),
                    description = "Return one automation run by id with its parent task metadata.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "runId",
                                required = true,
                                description = "Task run identifier",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val runId =
                arguments["runId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (runId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.run.get",
                    summary = "tasks.run.get requires a non-empty runId.",
                    field = "runId",
                )
            }
            val run =
                taskRepository.getRun(runId)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Task run $runId was not found.",
                        errorCode = "TASK_RUN_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TASK_RUN_NOT_FOUND")
                                put("toolName", "tasks.run.get")
                                put("runId", runId)
                            },
                    )
            val task =
                taskRepository.getTask(run.taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.run.get", taskId = run.taskId)
            ToolExecutionResult.success(
                summary = "Loaded run ${run.id} for task ${task.name}.",
                payload =
                    buildJsonObject {
                        put("taskId", task.id)
                        put("taskName", task.name)
                        put("run", run.toTaskRunHistoryPayload())
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.run.delete",
                    aliases =
                        listOf(
                            "task.run.delete",
                            "tasks.run.remove",
                            "task.run.remove",
                            "automations.run.delete",
                            "automation.run.delete",
                        ),
                    description = "Delete one automation run-history row by id after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "runId",
                                required = true,
                                description = "Task run identifier.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                description = "Must be CONFIRM.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val runId =
                arguments["runId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (runId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.run.delete",
                    summary = "tasks.run.delete requires a non-empty runId.",
                    field = "runId",
                )
            }
            val run =
                taskRepository.getRun(runId)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Task run $runId was not found.",
                        errorCode = "TASK_RUN_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TASK_RUN_NOT_FOUND")
                                put("toolName", "tasks.run.delete")
                                put("runId", runId)
                            },
                    )
            val task = taskRepository.getTask(run.taskId)
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Pass confirm=CONFIRM to delete automation run $runId.",
                    errorCode = "CONFIRMATION_REQUIRED",
                    payload =
                        buildJsonObject {
                            put("errorCode", "CONFIRMATION_REQUIRED")
                            put("toolName", "tasks.run.delete")
                            put("runId", run.id)
                            put("taskId", run.taskId)
                            put("field", "confirm")
                        },
                )
            }
            val deletedCount = taskRepository.deleteRun(run.id)
            ToolExecutionResult.success(
                summary = "Deleted automation run ${run.id}.",
                payload =
                    buildJsonObject {
                        put("deletedRunId", run.id)
                        put("taskId", run.taskId)
                        put("taskName", task?.name?.let(::JsonPrimitive) ?: JsonNull)
                        put("status", run.status.name)
                        put("scheduledAtIso", run.scheduledAt.toString())
                        put("deletedCount", deletedCount)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.run.retry",
                    aliases =
                        listOf(
                            "task.run.retry",
                            "tasks.retry_run",
                            "task.retry_run",
                            "automations.run.retry",
                            "automation.run.retry",
                        ),
                    description = "Queue a manual retry for a failed or skipped automation run.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "runId",
                                required = true,
                                description = "Failed or skipped automation run identifier",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val runId =
                arguments["runId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (runId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.run.retry",
                    summary = "tasks.run.retry requires a non-empty runId.",
                    field = "runId",
                )
            }
            val sourceRun =
                taskRepository.getRun(runId)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Task run $runId was not found.",
                        errorCode = "TASK_RUN_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TASK_RUN_NOT_FOUND")
                                put("toolName", "tasks.run.retry")
                                put("runId", runId)
                            },
                    )
            if (sourceRun.status != TaskRunStatus.Failure && sourceRun.status != TaskRunStatus.Skipped) {
                return@Entry ToolExecutionResult.failure(
                    summary =
                        "tasks.run.retry can only retry Failure or Skipped runs; " +
                            "${sourceRun.status.name} is not retryable.",
                    errorCode = "TASK_RUN_NOT_RETRYABLE",
                    payload =
                        buildJsonObject {
                            put("errorCode", "TASK_RUN_NOT_RETRYABLE")
                            put("toolName", "tasks.run.retry")
                            put("runId", sourceRun.id)
                            put("status", sourceRun.status.name)
                        },
                )
            }
            val task =
                taskRepository.getTask(sourceRun.taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.run.retry", taskId = sourceRun.taskId)
            val queuedAt = clock.instant()
            schedulerCoordinator.runNow(task.id)
            val reloadedTask = taskRepository.getTask(task.id) ?: task
            ToolExecutionResult.success(
                summary = "Queued retry for ${sourceRun.status.name} run ${sourceRun.id} of task ${task.name}.",
                payload =
                    buildJsonObject {
                        put("retryOfRunId", sourceRun.id)
                        put("queuedAtIso", queuedAt.toString())
                        put("trigger", "manual_retry")
                        put("sourceRun", sourceRun.toTaskRunHistoryPayload())
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
                ToolDescriptor(
                    name = "tasks.runs.recent",
                    aliases =
                        listOf(
                            "task.runs.recent",
                            "tasks.recent_runs",
                            "task.recent_runs",
                            "automations.runs.recent",
                            "automation.runs.recent",
                        ),
                    description = "Return recent automation runs across all tasks.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum run count. Defaults to 10.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = TASK_RUN_HISTORY_DEFAULT_LIMIT,
                )
            val runs = taskRepository.getRecentRuns(limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (runs.isEmpty()) {
                        "No recent automation runs found."
                    } else {
                        "Loaded ${runs.size} recent automation run(s)."
                    },
                payload =
                    buildJsonObject {
                        put("returnedCount", runs.size)
                        put("recentFirst", true)
                        put(
                            "runs",
                            buildJsonArray {
                                runs.forEach { run ->
                                    add(
                                        run.toTaskRunWithTaskPayload(
                                            task = taskRepository.getTask(run.taskId),
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
                    name = "tasks.runs.status",
                    aliases =
                        listOf(
                            "task.runs.status",
                            "tasks.status_runs",
                            "task.status_runs",
                            "automations.runs.status",
                            "automation.runs.status",
                        ),
                    description = "Return recent automation runs across all tasks for one run status.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "status",
                                required = true,
                                description = "Pending, Running, Success, Failure, or Skipped.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum run count. Defaults to 10.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val rawStatus =
                arguments.optionalText("status")
                    ?: return@Entry invalidTaskArguments(
                        toolName = "tasks.runs.status",
                        summary = "tasks.runs.status requires a non-empty status.",
                        field = "status",
                    )
            val status =
                TaskRunStatus.entries.firstOrNull { candidate ->
                    candidate.name.equals(rawStatus, ignoreCase = true)
                } ?: return@Entry invalidTaskArguments(
                    toolName = "tasks.runs.status",
                    summary = "tasks.runs.status received unsupported status: $rawStatus.",
                    field = "status",
                )
            val limit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = TASK_RUN_HISTORY_DEFAULT_LIMIT,
                )
            val runs = taskRepository.getRecentRunsByStatus(status = status, limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (runs.isEmpty()) {
                        "No recent ${status.name} automation runs found."
                    } else {
                        "Loaded ${runs.size} recent ${status.name} automation run(s)."
                    },
                payload =
                    buildJsonObject {
                        put("status", status.name)
                        put("returnedCount", runs.size)
                        put("recentFirst", true)
                        put(
                            "runs",
                            buildJsonArray {
                                runs.forEach { run ->
                                    add(
                                        run.toTaskRunWithTaskPayload(
                                            task = taskRepository.getTask(run.taskId),
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
                    name = "tasks.failures",
                    aliases =
                        listOf(
                            "task.failures",
                            "tasks.failed",
                            "task.failed",
                            "tasks.failed_runs",
                            "task.failed_runs",
                            "automations.failures",
                            "automation.failures",
                        ),
                    description = "Return recent failed automation runs across all tasks.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum run count. Defaults to 10.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = TASK_RUN_HISTORY_DEFAULT_LIMIT,
                )
            val runs = taskRepository.getRecentRunsByStatus(status = TaskRunStatus.Failure, limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (runs.isEmpty()) {
                        "No recent failed automation runs found."
                    } else {
                        "Loaded ${runs.size} recent failed automation run(s)."
                    },
                payload =
                    buildJsonObject {
                        put("returnedCount", runs.size)
                        put("recentFirst", true)
                        put(
                            "runs",
                            buildJsonArray {
                                runs.forEach { run ->
                                    add(
                                        run.toTaskRunWithTaskPayload(
                                            task = taskRepository.getTask(run.taskId),
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
                    name = "tasks.runs.clear",
                    aliases =
                        listOf(
                            "task.runs.clear",
                            "tasks.history.clear",
                            "task.history.clear",
                            "automations.runs.clear",
                            "automation.runs.clear",
                        ),
                    description = "Delete all run-history rows for one automation after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "taskId",
                                required = true,
                                description = "Task identifier whose run history should be cleared.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                description = "Must be CONFIRM.",
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
                    toolName = "tasks.runs.clear",
                    summary = "tasks.runs.clear requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.runs.clear", taskId = taskId)
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Pass confirm=CONFIRM to clear automation run history for task ${task.name}.",
                    errorCode = "CONFIRMATION_REQUIRED",
                    payload =
                        buildJsonObject {
                            put("errorCode", "CONFIRMATION_REQUIRED")
                            put("toolName", "tasks.runs.clear")
                            put("taskId", task.id)
                            put("field", "confirm")
                        },
                )
            }
            val deletedCount = taskRepository.clearRunsForTask(task.id)
            ToolExecutionResult.success(
                summary = "Cleared $deletedCount run-history row(s) for task ${task.name}.",
                payload =
                    buildJsonObject {
                        put("taskId", task.id)
                        put("taskName", task.name)
                        put("deletedCount", deletedCount)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.runs.clear_status",
                    aliases =
                        listOf(
                            "task.runs.clear_status",
                            "tasks.runs.status.clear",
                            "task.runs.status.clear",
                            "automations.runs.clear_status",
                            "automation.runs.clear_status",
                        ),
                    description = "Delete run-history rows for one automation run status after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "status",
                                required = true,
                                description = "Pending, Running, Success, Failure, or Skipped.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                description = "Must be CONFIRM.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val rawStatus =
                arguments.optionalText("status")
                    ?: return@Entry invalidTaskArguments(
                        toolName = "tasks.runs.clear_status",
                        summary = "tasks.runs.clear_status requires a non-empty status.",
                        field = "status",
                    )
            val status =
                TaskRunStatus.entries.firstOrNull { candidate ->
                    candidate.name.equals(rawStatus, ignoreCase = true)
                } ?: return@Entry invalidTaskArguments(
                    toolName = "tasks.runs.clear_status",
                    summary = "tasks.runs.clear_status received unsupported status: $rawStatus.",
                    field = "status",
                )
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Pass confirm=CONFIRM to clear ${status.name} automation run history.",
                    errorCode = "CONFIRMATION_REQUIRED",
                    payload =
                        buildJsonObject {
                            put("errorCode", "CONFIRMATION_REQUIRED")
                            put("toolName", "tasks.runs.clear_status")
                            put("status", status.name)
                            put("field", "confirm")
                        },
                )
            }
            val deletedCount = taskRepository.clearRunsByStatus(status)
            ToolExecutionResult.success(
                summary = "Cleared $deletedCount ${status.name} automation run-history row(s).",
                payload =
                    buildJsonObject {
                        put("status", status.name)
                        put("deletedCount", deletedCount)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.runs.trim",
                    aliases =
                        listOf(
                            "task.runs.trim",
                            "tasks.runs.prune",
                            "task.runs.prune",
                            "automations.runs.trim",
                            "automation.runs.trim",
                        ),
                    description = "Delete automation run-history rows older than an ISO-8601 cutoff after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "olderThanIso",
                                description = "ISO-8601 cutoff. Runs scheduled before this instant are deleted.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                description = "Must be CONFIRM.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val olderThanIso =
                arguments.optionalText("olderThanIso")
                    ?: return@Entry invalidTaskArguments(
                        toolName = "tasks.runs.trim",
                        summary = "tasks.runs.trim requires a non-empty olderThanIso.",
                        field = "olderThanIso",
                    )
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Pass confirm=CONFIRM to trim old automation run history.",
                    errorCode = "CONFIRMATION_REQUIRED",
                    payload =
                        buildJsonObject {
                            put("errorCode", "CONFIRMATION_REQUIRED")
                            put("toolName", "tasks.runs.trim")
                            put("field", "confirm")
                        },
                )
            }
            val cutoff =
                try {
                    Instant.parse(olderThanIso)
                } catch (_: DateTimeParseException) {
                    return@Entry invalidTaskArguments(
                        toolName = "tasks.runs.trim",
                        summary = "tasks.runs.trim received an invalid olderThanIso.",
                        field = "olderThanIso",
                    )
                }
            val deletedCount = taskRepository.trimRunsOlderThan(cutoff)
            ToolExecutionResult.success(
                summary = "Trimmed $deletedCount automation run(s) older than $cutoff.",
                payload =
                    buildJsonObject {
                        put("olderThanIso", cutoff.toString())
                        put("deletedCount", deletedCount)
                    },
            )
        },
    )
