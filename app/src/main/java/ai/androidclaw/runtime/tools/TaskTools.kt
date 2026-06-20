package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.Session
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

internal fun taskToolEntries(
    taskRepository: TaskRepository,
    sessionRepository: SessionRepository,
    schedulerCoordinator: SchedulerCoordinator,
    clock: Clock,
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
                    name = "tasks.export",
                    aliases =
                        listOf(
                            "task.export",
                            "automations.export",
                            "automation.export",
                            "tasks.backup",
                            "task.backup",
                            "automations.backup",
                            "automation.backup",
                        ),
                    description = "Export bounded automation definitions without run history.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum task count to export. Defaults to 50, max 100.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDisabled",
                                description = "Set false to export only enabled automations. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includePrompts",
                                description = "Set true to include full task prompts. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "includeTargetSessionMetadata",
                                description = "Set false to omit resolved target-session title metadata. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit exportMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TASK_EXPORT_DEFAULT_LIMIT,
                    ).coerceIn(0, TASK_EXPORT_MAX_LIMIT)
            val includeDisabled = arguments.optionalBoolean("includeDisabled", defaultValue = true)
            val includePrompts = arguments.optionalBoolean("includePrompts", defaultValue = false)
            val includeTargetSessionMetadata = arguments.optionalBoolean("includeTargetSessionMetadata", defaultValue = true)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val allTasks = taskRepository.observeTasks().first()
            val candidateTasks =
                allTasks
                    .asSequence()
                    .filter { task -> includeDisabled || task.enabled }
                    .sortedByDescending { task -> task.updatedAt }
                    .toList()
            val exportedTasks = candidateTasks.take(limit)
            val targetSessionsById = mutableMapOf<String, Session?>()
            if (includeTargetSessionMetadata) {
                exportedTasks
                    .mapNotNull(Task::targetSessionId)
                    .distinct()
                    .forEach { sessionId ->
                        targetSessionsById[sessionId] = sessionRepository.getSession(sessionId)
                    }
            }
            val exportMarkdown =
                if (includeMarkdown) {
                    exportedTasks.toTaskExportMarkdown(
                        totalTaskCount = allTasks.size,
                        candidateTaskCount = candidateTasks.size,
                        limit = limit,
                        includeDisabled = includeDisabled,
                        includePrompts = includePrompts,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (exportedTasks.isEmpty()) {
                        "Prepared empty automation export."
                    } else {
                        "Prepared automation export with ${exportedTasks.size} task definition(s)."
                    },
                payload =
                    buildJsonObject {
                        put("exportFormat", TASK_EXPORT_FORMAT)
                        put("exportVersion", TASK_EXPORT_VERSION)
                        put("generatedAtIso", clock.instant().toString())
                        put("taskLimit", limit)
                        put("totalTaskCount", allTasks.size)
                        put("candidateTaskCount", candidateTasks.size)
                        put("exportedTaskCount", exportedTasks.size)
                        put("omittedTaskCount", (candidateTasks.size - exportedTasks.size).coerceAtLeast(0))
                        put("includeDisabled", includeDisabled)
                        put("disabledTaskCount", allTasks.count { task -> !task.enabled })
                        put("promptsIncluded", includePrompts)
                        put("promptBodiesIncluded", includePrompts)
                        put("fullPromptBodiesIncluded", includePrompts)
                        put("runHistoryIncluded", false)
                        put("providerMetaIncluded", false)
                        put("targetSessionMetadataIncluded", includeTargetSessionMetadata)
                        put("includeMarkdown", includeMarkdown)
                        put("scheduleKindStats", candidateTasks.toTaskExportScheduleStatsPayload())
                        put("executionModeStats", candidateTasks.toTaskExportExecutionModeStatsPayload())
                        put(
                            "tasks",
                            buildJsonArray {
                                exportedTasks.forEach { task ->
                                    add(
                                        task.toTaskExportPayload(
                                            includePrompt = includePrompts,
                                            targetSession = targetSessionsById[task.targetSessionId],
                                            includeTargetSessionMetadata = includeTargetSessionMetadata,
                                        ),
                                    )
                                }
                            },
                        )
                        put("exportMarkdown", exportMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.import",
                    aliases =
                        listOf(
                            "task.import",
                            "automations.import",
                            "automation.import",
                            "tasks.restore",
                            "task.restore",
                            "automations.restore",
                            "automation.restore",
                        ),
                    description = "Import bounded automation definitions from a tasks.export payload.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "tasks",
                                description = "Array of exported task definitions, or pass export.tasks.",
                            ),
                            ToolArgumentSpec(
                                name = "export",
                                description = "Optional tasks.export payload containing a tasks array.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum task definitions to scan. Defaults to 50, max 100.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDisabled",
                                description = "Set false to skip disabled export entries. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "enableImported",
                                description = "Set true to import enabled source tasks as enabled and schedule them. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "preserveTargetSessionIds",
                                description = "Set true to preserve targetSessionId only when it exists and is active locally. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "includePrompts",
                                description = "Set true to include prompt bodies in result payloads. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "dryRun",
                                description = "Set true to preview importable automations without writing. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                description = "Must be CONFIRM unless dryRun=true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val dryRun = arguments.optionalBoolean("dryRun", defaultValue = false)
            if (!dryRun && arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry missingTaskImportConfirmationResult()
            }
            val rawEntries =
                when (val parsedEntries = arguments.taskImportEntries()) {
                    is TaskImportEntriesParseResult.Failure -> return@Entry parsedEntries.result
                    is TaskImportEntriesParseResult.Success -> parsedEntries.entries
                }
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TASK_IMPORT_DEFAULT_LIMIT,
                    ).coerceIn(0, TASK_IMPORT_MAX_LIMIT)
            val includeDisabled = arguments.optionalBoolean("includeDisabled", defaultValue = true)
            val enableImported = arguments.optionalBoolean("enableImported", defaultValue = false)
            val preserveTargetSessionIds = arguments.optionalBoolean("preserveTargetSessionIds", defaultValue = false)
            val includePrompts = arguments.optionalBoolean("includePrompts", defaultValue = false)
            val scannedEntries = rawEntries.take(limit)
            val candidates = mutableListOf<TaskImportCandidate>()
            val skipped = mutableListOf<TaskImportSkippedEntry>()
            scannedEntries.forEachIndexed { sourceIndex, element ->
                when (val parsedCandidate = element.toTaskImportCandidate(sourceIndex = sourceIndex)) {
                    is TaskImportCandidateParseResult.Candidate -> {
                        if (!includeDisabled && !parsedCandidate.candidate.sourceEnabled) {
                            skipped +=
                                TaskImportSkippedEntry(
                                    sourceIndex = sourceIndex,
                                    code = "tasks.import.disabled_skipped",
                                    summary = "Disabled export entry skipped because includeDisabled=false.",
                                )
                        } else {
                            candidates += parsedCandidate.candidate
                        }
                    }
                    is TaskImportCandidateParseResult.Skipped -> skipped += parsedCandidate.skipped
                }
            }
            val targetSessionsBySourceId = mutableMapOf<String, Session?>()
            if (preserveTargetSessionIds) {
                candidates
                    .mapNotNull(TaskImportCandidate::sourceTargetSessionId)
                    .distinct()
                    .forEach { sessionId ->
                        targetSessionsBySourceId[sessionId] = sessionRepository.getSession(sessionId)
                    }
            }
            val importedTasks = mutableListOf<TaskImportedItem>()
            if (!dryRun) {
                candidates.forEach { candidate ->
                    val importedTargetSessionId = candidate.importTargetSessionId(preserveTargetSessionIds, targetSessionsBySourceId)
                    val importedEnabled = candidate.importedEnabled(enableImported)
                    val createdTask =
                        taskRepository.createTask(
                            name = candidate.name,
                            prompt = candidate.prompt,
                            schedule = candidate.schedule,
                            executionMode = candidate.executionMode,
                            targetSessionId = importedTargetSessionId,
                            precise = candidate.precise,
                            maxRetries = candidate.maxRetries,
                        )
                    val finalTask =
                        if (importedEnabled) {
                            createdTask
                        } else {
                            createdTask.copy(
                                enabled = false,
                                updatedAt = clock.instant(),
                            )
                        }
                    if (finalTask != createdTask) {
                        taskRepository.updateTask(finalTask)
                        schedulerCoordinator.cancelTask(finalTask.id)
                    } else {
                        schedulerCoordinator.scheduleTask(finalTask.id)
                    }
                    importedTasks +=
                        TaskImportedItem(
                            candidate = candidate,
                            task = taskRepository.getTask(finalTask.id) ?: finalTask,
                            importedTargetSessionId = importedTargetSessionId,
                            importedEnabled = importedEnabled,
                        )
                }
            }
            ToolExecutionResult.success(
                summary =
                    if (dryRun) {
                        "Prepared dry-run automation import with ${candidates.size} importable task definition(s)."
                    } else {
                        "Imported ${importedTasks.size} automation definition(s); skipped ${skipped.size}."
                    },
                payload =
                    buildJsonObject {
                        put("importFormat", TASK_IMPORT_FORMAT)
                        put("importVersion", TASK_IMPORT_VERSION)
                        put("acceptedExportFormat", TASK_EXPORT_FORMAT)
                        put("acceptedExportVersion", TASK_EXPORT_VERSION)
                        put("taskLimit", limit)
                        put("importLimit", limit)
                        put("dryRun", dryRun)
                        put("includeDisabled", includeDisabled)
                        put("enableImported", enableImported)
                        put("preserveTargetSessionIds", preserveTargetSessionIds)
                        put("promptsIncluded", includePrompts)
                        put("promptBodiesIncluded", includePrompts)
                        put("runHistoryImported", false)
                        put("runHistoryIncluded", false)
                        put("providerMetaImported", false)
                        put("providerMetaIncluded", false)
                        put("receivedTaskCount", rawEntries.size)
                        put("scannedTaskCount", scannedEntries.size)
                        put("omittedInputTaskCount", (rawEntries.size - scannedEntries.size).coerceAtLeast(0))
                        put("importableTaskCount", candidates.size)
                        put("importedTaskCount", importedTasks.size)
                        put("skippedTaskCount", skipped.size)
                        put("invalidTaskCount", skipped.count { entry -> entry.code.startsWith("tasks.import.invalid") })
                        put("disabledTaskSkippedCount", skipped.count { entry -> entry.code == "tasks.import.disabled_skipped" })
                        put("enabledImportedTaskCount", importedTasks.count { item -> item.importedEnabled })
                        put("targetSessionPreservedCount", candidates.count { candidate -> candidate.targetSessionPreserved(preserveTargetSessionIds, targetSessionsBySourceId) })
                        put("targetSessionDroppedCount", candidates.count { candidate -> candidate.targetSessionDropped(preserveTargetSessionIds, targetSessionsBySourceId) })
                        put(
                            "candidateTasks",
                            buildJsonArray {
                                candidates.forEach { candidate ->
                                    add(
                                        candidate.toTaskImportCandidatePayload(
                                            includePrompt = includePrompts,
                                            enableImported = enableImported,
                                            preserveTargetSessionIds = preserveTargetSessionIds,
                                            targetSessionsBySourceId = targetSessionsBySourceId,
                                        ),
                                    )
                                }
                            },
                        )
                        put(
                            "importedTasks",
                            buildJsonArray {
                                importedTasks.forEach { importedItem ->
                                    add(importedItem.toTaskImportedPayload(includePrompt = includePrompts))
                                }
                            },
                        )
                        put(
                            "skippedTasks",
                            buildJsonArray {
                                skipped.forEach { skippedEntry ->
                                    add(skippedEntry.toTaskImportSkippedPayload())
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.disable_all",
                    aliases =
                        listOf(
                            "task.disable_all",
                            "tasks.pause_all",
                            "task.pause_all",
                            "automations.pause_all",
                            "automation.pause_all",
                        ),
                    description = "Disable every currently enabled automation after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "confirm",
                                description = "Required as CONFIRM to pause all automations.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Confirm pausing all automations with confirm=CONFIRM.",
                    errorCode = "CONFIRMATION_REQUIRED",
                    payload =
                        buildJsonObject {
                            put("errorCode", "CONFIRMATION_REQUIRED")
                            put("toolName", "tasks.disable_all")
                            put("field", "confirm")
                        },
                )
            }
            val tasks = taskRepository.observeTasks().first()
            val updatedAt = clock.instant()
            val changedTasks = tasks.filter { task -> task.enabled }
            val updatedTasks =
                changedTasks.map { task ->
                    val updatedTask =
                        task.copy(
                            enabled = false,
                            updatedAt = updatedAt,
                        )
                    taskRepository.updateTask(updatedTask)
                    schedulerCoordinator.cancelTask(updatedTask.id)
                    taskRepository.getTask(updatedTask.id) ?: updatedTask
                }
            ToolExecutionResult.success(
                summary = "Paused ${updatedTasks.size} automation(s).",
                payload =
                    buildJsonObject {
                        put("updatedAtIso", updatedAt.toString())
                        put("taskCount", tasks.size)
                        put("updatedTaskCount", updatedTasks.size)
                        put("unchangedTaskCount", tasks.size - changedTasks.size)
                        put("updatedTasksOmitted", updatedTasks.taskBulkToggleOmittedCount())
                        put("enabled", false)
                        put("updatedTasks", updatedTasks.toTaskBulkToggleJsonArray())
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.enable_all",
                    aliases =
                        listOf(
                            "task.enable_all",
                            "tasks.resume_all",
                            "task.resume_all",
                            "automations.resume_all",
                            "automation.resume_all",
                        ),
                    description = "Enable every currently disabled automation after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "confirm",
                                description = "Required as CONFIRM to resume all automations.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Confirm resuming all automations with confirm=CONFIRM.",
                    errorCode = "CONFIRMATION_REQUIRED",
                    payload =
                        buildJsonObject {
                            put("errorCode", "CONFIRMATION_REQUIRED")
                            put("toolName", "tasks.enable_all")
                            put("field", "confirm")
                        },
                )
            }
            val tasks = taskRepository.observeTasks().first()
            val updatedAt = clock.instant()
            val changedTasks = tasks.filterNot { task -> task.enabled }
            val updatedTasks =
                changedTasks.map { task ->
                    val updatedTask =
                        task.copy(
                            enabled = true,
                            updatedAt = updatedAt,
                        )
                    taskRepository.updateTask(updatedTask)
                    schedulerCoordinator.scheduleTask(updatedTask.id)
                    taskRepository.getTask(updatedTask.id) ?: updatedTask
                }
            ToolExecutionResult.success(
                summary = "Resumed ${updatedTasks.size} automation(s).",
                payload =
                    buildJsonObject {
                        put("updatedAtIso", updatedAt.toString())
                        put("taskCount", tasks.size)
                        put("updatedTaskCount", updatedTasks.size)
                        put("unchangedTaskCount", tasks.size - changedTasks.size)
                        put("updatedTasksOmitted", updatedTasks.taskBulkToggleOmittedCount())
                        put("enabled", true)
                        put("updatedTasks", updatedTasks.toTaskBulkToggleJsonArray())
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.get",
                    aliases = listOf("task.get"),
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
            descriptor =
                ToolDescriptor(
                    name = "tasks.handoff",
                    aliases =
                        listOf(
                            "task.handoff",
                            "tasks.snapshot",
                            "task.snapshot",
                            "automation.handoff",
                            "automation.snapshot",
                        ),
                    description = "Return a compact automation handoff with schedule metadata, prompt snippet, and recent runs.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "taskId",
                                required = true,
                                description = "Task identifier.",
                            ),
                            ToolArgumentSpec(
                                name = "runLimit",
                                description = "Recent run count. Defaults to 5, max 20.",
                            ),
                            ToolArgumentSpec(
                                name = "includePrompt",
                                description = "Set false to omit the prompt snippet. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit handoffMarkdown. Defaults to true.",
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
                    toolName = "tasks.handoff",
                    summary = "tasks.handoff requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.handoff", taskId = taskId)
            val runLimit =
                arguments
                    .optionalInt(
                        field = "runLimit",
                        defaultValue = TASK_HANDOFF_DEFAULT_RUN_LIMIT,
                    ).coerceIn(0, TASK_HANDOFF_MAX_RUN_LIMIT)
            val includePrompt = arguments.optionalBoolean("includePrompt", defaultValue = true)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val recentRuns = taskRepository.getRecentRuns(taskId = task.id, limit = runLimit)
            val promptSnippet = task.prompt.toMessageSearchSnippet().takeIf { includePrompt }
            val handoffMarkdown =
                if (includeMarkdown) {
                    task.toTaskHandoffMarkdown(
                        promptSnippet = promptSnippet,
                        recentRuns = recentRuns,
                        runLimit = runLimit,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary = "Prepared automation handoff for task ${task.name}.",
                payload =
                    buildJsonObject {
                        put("taskId", task.id)
                        put("name", task.name)
                        put("enabled", task.enabled)
                        put("scheduleKind", task.schedule.toTaskSearchKind())
                        put("schedule", task.schedule.toPayload())
                        put("executionMode", task.executionMode.name)
                        put("targetSessionId", task.targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
                        put("preciseRequested", task.precise)
                        put("nextRunAtIso", task.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("lastRunAtIso", task.lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("failureCount", task.failureCount)
                        put("maxRetries", task.maxRetries)
                        put("createdAtIso", task.createdAt.toString())
                        put("updatedAtIso", task.updatedAt.toString())
                        put("promptIncluded", includePrompt)
                        put("promptSnippet", promptSnippet?.let(::JsonPrimitive) ?: JsonNull)
                        put("promptLength", task.prompt.length)
                        put("promptTruncated", promptSnippet?.let { it.length < task.prompt.length } ?: false)
                        put("runLimit", runLimit)
                        put("runCount", recentRuns.size)
                        put("handoffMarkdown", handoffMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                        put(
                            "recentRuns",
                            buildJsonArray {
                                recentRuns.forEach { run ->
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
                    name = "tasks.preview.occurrences",
                    aliases =
                        listOf(
                            "task.preview.occurrences",
                            "tasks.schedule.preview_occurrences",
                            "task.schedule.preview_occurrences",
                            "automations.preview.occurrences",
                            "automation.preview.occurrences",
                        ),
                    description = "Preview multiple run times for an unsaved automation schedule.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "scheduleKind",
                                required = true,
                                description = "once | interval | cron",
                            ),
                            ToolArgumentSpec(
                                name = "atIso",
                                description = "ISO-8601 instant for once schedules.",
                            ),
                            ToolArgumentSpec(
                                name = "anchorAtIso",
                                description = "ISO-8601 anchor instant for interval schedules.",
                            ),
                            ToolArgumentSpec(
                                name = "repeatEveryMinutes",
                                description = "Positive interval minutes; must meet scheduler minimum.",
                            ),
                            ToolArgumentSpec(
                                name = "cronExpression",
                                description = "Five-field cron expression for cron schedules.",
                            ),
                            ToolArgumentSpec(
                                name = "timezone",
                                description = "IANA timezone id for cron schedules.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum occurrence count. Defaults to 5, max 20.",
                            ),
                            ToolArgumentSpec(
                                name = "afterIso",
                                description = "Optional exclusive ISO-8601 lower bound. Defaults to now.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val now = clock.instant()
            val preview =
                try {
                    parseTaskSchedulePreview(
                        arguments = arguments,
                        capabilities = schedulerCoordinator.capabilities(),
                        now = now,
                        toolName = "tasks.preview.occurrences",
                    )
                } catch (error: IllegalArgumentException) {
                    return@Entry invalidTaskArguments(
                        toolName = "tasks.preview.occurrences",
                        summary = error.message ?: "tasks.preview.occurrences received invalid arguments.",
                    )
                }
            val schedulePreview =
                when (preview) {
                    is TaskToolParseResult.Failure -> return@Entry preview.result
                    is TaskToolParseResult.Success -> preview.value
                }
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TASK_OCCURRENCES_DEFAULT_LIMIT,
                    ).coerceIn(0, TASK_OCCURRENCES_MAX_LIMIT)
            val after =
                arguments.optionalText("afterIso")?.let { rawAfterIso ->
                    try {
                        Instant.parse(rawAfterIso)
                    } catch (_: DateTimeParseException) {
                        return@Entry invalidTaskArguments(
                            toolName = "tasks.preview.occurrences",
                            summary = "tasks.preview.occurrences received an invalid afterIso.",
                            field = "afterIso",
                        )
                    }
                } ?: now
            val occurrences =
                schedulePreview.schedule.computeScheduledOccurrences(
                    after = after,
                    limit = limit,
                )
            ToolExecutionResult.success(
                summary =
                    if (occurrences.isEmpty()) {
                        "No scheduled occurrences found for preview schedule."
                    } else {
                        "Previewed ${occurrences.size} scheduled occurrence(s)."
                    },
                payload =
                    buildJsonObject {
                        put("nowIso", now.toString())
                        put("afterIso", after.toString())
                        put("scheduleKind", schedulePreview.schedule.toTaskSearchKind())
                        put("schedule", schedulePreview.schedule.toPayload())
                        put("nextRunAtIso", schedulePreview.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("limit", limit)
                        put("occurrenceCount", occurrences.size)
                        put(
                            "occurrences",
                            buildJsonArray {
                                occurrences.forEachIndexed { index, occurrence ->
                                    add(
                                        schedulePreview.schedule.toScheduledOccurrencePayload(
                                            occurrence = occurrence,
                                            index = index,
                                            after = after,
                                            now = now,
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
                    name = "tasks.preview",
                    aliases =
                        listOf(
                            "task.preview",
                            "tasks.schedule.preview",
                            "task.schedule.preview",
                            "automations.preview",
                            "automation.preview",
                        ),
                    description = "Preview an automation schedule without creating or updating a task.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "scheduleKind",
                                required = true,
                                description = "once | interval | cron",
                            ),
                            ToolArgumentSpec(
                                name = "atIso",
                                description = "ISO-8601 instant for once schedules.",
                            ),
                            ToolArgumentSpec(
                                name = "anchorAtIso",
                                description = "ISO-8601 anchor instant for interval schedules.",
                            ),
                            ToolArgumentSpec(
                                name = "repeatEveryMinutes",
                                description = "Positive interval minutes; must meet scheduler minimum.",
                            ),
                            ToolArgumentSpec(
                                name = "cronExpression",
                                description = "Five-field cron expression for cron schedules.",
                            ),
                            ToolArgumentSpec(
                                name = "timezone",
                                description = "IANA timezone id for cron schedules.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val now = clock.instant()
            val preview =
                try {
                    parseTaskSchedulePreview(
                        arguments = arguments,
                        capabilities = schedulerCoordinator.capabilities(),
                        now = now,
                    )
                } catch (error: IllegalArgumentException) {
                    return@Entry invalidTaskArguments(
                        toolName = "tasks.preview",
                        summary = error.message ?: "tasks.preview received invalid arguments.",
                    )
                }
            when (preview) {
                is TaskToolParseResult.Failure -> preview.result
                is TaskToolParseResult.Success ->
                    ToolExecutionResult.success(
                        summary =
                            if (preview.value.nextRunAt == null) {
                                "Previewed schedule; no next run was produced."
                            } else {
                                "Previewed schedule."
                            },
                        payload = preview.value.toPayload(now = now),
                    )
            }
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.occurrences",
                    aliases =
                        listOf(
                            "task.occurrences",
                            "tasks.schedule.occurrences",
                            "task.schedule.occurrences",
                            "automations.occurrences",
                            "automation.occurrences",
                        ),
                    description = "Preview upcoming scheduled run times for one automation without mutating it.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "taskId",
                                required = true,
                                description = "Task identifier.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum occurrence count. Defaults to 5, max 20.",
                            ),
                            ToolArgumentSpec(
                                name = "afterIso",
                                description = "Optional exclusive ISO-8601 lower bound. Defaults to now.",
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
                    toolName = "tasks.occurrences",
                    summary = "tasks.occurrences requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.occurrences", taskId = taskId)
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TASK_OCCURRENCES_DEFAULT_LIMIT,
                    ).coerceIn(0, TASK_OCCURRENCES_MAX_LIMIT)
            val after =
                arguments.optionalText("afterIso")?.let { rawAfterIso ->
                    try {
                        Instant.parse(rawAfterIso)
                    } catch (_: DateTimeParseException) {
                        return@Entry invalidTaskArguments(
                            toolName = "tasks.occurrences",
                            summary = "tasks.occurrences received an invalid afterIso.",
                            field = "afterIso",
                        )
                    }
                } ?: clock.instant()
            val now = clock.instant()
            val occurrences =
                task.schedule.computeScheduledOccurrences(
                    after = after,
                    limit = limit,
                )
            ToolExecutionResult.success(
                summary =
                    if (occurrences.isEmpty()) {
                        "No scheduled occurrences found for task ${task.name}."
                    } else {
                        "Loaded ${occurrences.size} scheduled occurrence(s) for task ${task.name}."
                    },
                payload =
                    buildJsonObject {
                        put("taskId", task.id)
                        put("taskName", task.name)
                        put("enabled", task.enabled)
                        put("scheduleKind", task.schedule.toTaskSearchKind())
                        put("nextRunAtIso", task.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("nowIso", now.toString())
                        put("afterIso", after.toString())
                        put("limit", limit)
                        put("occurrenceCount", occurrences.size)
                        put(
                            "occurrences",
                            buildJsonArray {
                                occurrences.forEachIndexed { index, occurrence ->
                                    add(
                                        task.schedule.toScheduledOccurrencePayload(
                                            occurrence = occurrence,
                                            index = index,
                                            after = after,
                                            now = now,
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
                    name = "tasks.timeline",
                    aliases =
                        listOf(
                            "task.timeline",
                            "tasks.calendar",
                            "task.calendar",
                            "automations.timeline",
                            "automation.timeline",
                            "automations.calendar",
                            "automation.calendar",
                        ),
                    description = "Return a global upcoming automation occurrence timeline without mutating tasks.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum global occurrence count. Defaults to 20, max 100.",
                            ),
                            ToolArgumentSpec(
                                name = "perTaskLimit",
                                description = "Maximum occurrences generated per task. Defaults to 5, max 20.",
                            ),
                            ToolArgumentSpec(
                                name = "afterIso",
                                description = "Optional exclusive ISO-8601 lower bound. Defaults to now.",
                            ),
                            ToolArgumentSpec(
                                name = "beforeIso",
                                description = "Optional inclusive ISO-8601 upper bound.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDisabled",
                                description = "Set true to include disabled automations. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "includePromptSnippets",
                                description = "Set false to omit prompt snippets. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit timelineMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val now = clock.instant()
            val after =
                arguments.optionalText("afterIso")?.let { rawAfterIso ->
                    try {
                        Instant.parse(rawAfterIso)
                    } catch (_: DateTimeParseException) {
                        return@Entry invalidTaskArguments(
                            toolName = "tasks.timeline",
                            summary = "tasks.timeline received an invalid afterIso.",
                            field = "afterIso",
                        )
                    }
                } ?: now
            val before =
                arguments.optionalText("beforeIso")?.let { rawBeforeIso ->
                    try {
                        Instant.parse(rawBeforeIso)
                    } catch (_: DateTimeParseException) {
                        return@Entry invalidTaskArguments(
                            toolName = "tasks.timeline",
                            summary = "tasks.timeline received an invalid beforeIso.",
                            field = "beforeIso",
                        )
                    }
                }
            if (before != null && !before.isAfter(after)) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.timeline",
                    summary = "tasks.timeline requires beforeIso to be after afterIso.",
                    field = "beforeIso",
                )
            }
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TASK_TIMELINE_DEFAULT_LIMIT,
                    ).coerceIn(0, TASK_TIMELINE_MAX_LIMIT)
            val perTaskLimit =
                arguments
                    .optionalInt(
                        field = "perTaskLimit",
                        defaultValue = TASK_TIMELINE_DEFAULT_PER_TASK_LIMIT,
                    ).coerceIn(0, TASK_TIMELINE_MAX_PER_TASK_LIMIT)
            val includeDisabled = arguments.optionalBoolean("includeDisabled", defaultValue = false)
            val includePromptSnippets = arguments.optionalBoolean("includePromptSnippets", defaultValue = true)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val allTasks = taskRepository.observeTasks().first()
            val candidateTasks =
                allTasks.filter { task ->
                    includeDisabled || task.enabled
                }
            val generatedOccurrences =
                if (limit == 0 || perTaskLimit == 0) {
                    emptyList()
                } else {
                    candidateTasks.flatMap { task ->
                        task.schedule
                            .computeScheduledOccurrences(
                                after = after,
                                limit = perTaskLimit,
                            ).filter { occurrence ->
                                before == null || !occurrence.isAfter(before)
                            }.mapIndexed { taskOccurrenceIndex, occurrence ->
                                TaskTimelineOccurrence(
                                    task = task,
                                    runAt = occurrence,
                                    taskOccurrenceIndex = taskOccurrenceIndex,
                                )
                            }
                    }
                }
            val timelineOccurrences =
                generatedOccurrences
                    .sortedWith(
                        compareBy<TaskTimelineOccurrence> { occurrence -> occurrence.runAt }
                            .thenBy { occurrence -> occurrence.task.name }
                            .thenBy { occurrence -> occurrence.task.id }
                            .thenBy { occurrence -> occurrence.taskOccurrenceIndex },
                    ).take(limit)
            val targetSessions =
                timelineOccurrences
                    .mapNotNull { occurrence -> occurrence.task.targetSessionId }
                    .distinct()
                    .associateWith { sessionId -> sessionRepository.getSession(sessionId) }
            val timelineMarkdown =
                if (includeMarkdown) {
                    timelineOccurrences.toTaskTimelineMarkdown(
                        now = now,
                        after = after,
                        before = before,
                        limit = limit,
                        perTaskLimit = perTaskLimit,
                        candidateTaskCount = candidateTasks.size,
                        generatedOccurrenceCount = generatedOccurrences.size,
                        includeDisabled = includeDisabled,
                        includePromptSnippets = includePromptSnippets,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (timelineOccurrences.isEmpty()) {
                        "Prepared empty automation timeline."
                    } else {
                        "Prepared automation timeline with ${timelineOccurrences.size} occurrence(s)."
                    },
                payload =
                    buildJsonObject {
                        put("nowIso", now.toString())
                        put("afterIso", after.toString())
                        put("beforeIso", before?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("limit", limit)
                        put("perTaskLimit", perTaskLimit)
                        put("includeDisabled", includeDisabled)
                        put("includePromptSnippets", includePromptSnippets)
                        put("includeMarkdown", includeMarkdown)
                        put("promptBodiesIncluded", false)
                        put("totalTaskCount", allTasks.size)
                        put("candidateTaskCount", candidateTasks.size)
                        put("candidateEnabledTaskCount", candidateTasks.count { task -> task.enabled })
                        put("candidateDisabledTaskCount", candidateTasks.count { task -> !task.enabled })
                        put("generatedOccurrenceCount", generatedOccurrences.size)
                        put("occurrenceCount", timelineOccurrences.size)
                        put("includedTaskCount", timelineOccurrences.map { occurrence -> occurrence.task.id }.distinct().size)
                        put("omittedOccurrenceCount", (generatedOccurrences.size - timelineOccurrences.size).coerceAtLeast(0))
                        put(
                            "occurrences",
                            buildJsonArray {
                                timelineOccurrences.forEachIndexed { index, occurrence ->
                                    add(
                                        occurrence.toTaskTimelinePayload(
                                            index = index,
                                            after = after,
                                            now = now,
                                            targetSession =
                                                occurrence.task.targetSessionId
                                                    ?.let { sessionId -> targetSessions[sessionId] },
                                            includePromptSnippet = includePromptSnippets,
                                        ),
                                    )
                                }
                            },
                        )
                        put("timelineMarkdown", timelineMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.reschedule",
                    aliases =
                        listOf(
                            "task.reschedule",
                            "tasks.recompute_next",
                            "task.recompute_next",
                            "automations.reschedule",
                            "automation.reschedule",
                        ),
                    description = "Recompute an automation's next run from its schedule without executing it.",
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
                    toolName = "tasks.reschedule",
                    summary = "tasks.reschedule requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.reschedule", taskId = taskId)
            val rescheduledAt = clock.instant()
            val recalculatedNextRunAt = schedulerCoordinator.taskPlanner.nextScheduledRun(task, rescheduledAt)
            val updatedTask =
                task.copy(
                    nextRunAt = recalculatedNextRunAt,
                    failureCount = 0,
                    updatedAt = rescheduledAt,
                )
            taskRepository.updateTask(updatedTask)
            if (updatedTask.enabled) {
                schedulerCoordinator.scheduleTask(updatedTask.id)
            } else {
                schedulerCoordinator.cancelTask(updatedTask.id)
            }
            val reloadedTask = taskRepository.getTask(updatedTask.id) ?: updatedTask
            ToolExecutionResult.success(
                summary =
                    if (reloadedTask.nextRunAt == null) {
                        "Rescheduled task ${reloadedTask.name}; no future run remains."
                    } else {
                        "Rescheduled task ${reloadedTask.name}."
                    },
                payload =
                    buildJsonObject {
                        put("rescheduledAtIso", rescheduledAt.toString())
                        put("previousNextRunAtIso", task.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("nextRunAtIso", reloadedTask.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("failureCountCleared", task.failureCount != 0)
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
                    name = "tasks.snooze",
                    aliases =
                        listOf(
                            "task.snooze",
                            "tasks.postpone",
                            "task.postpone",
                            "automations.snooze",
                            "automation.snooze",
                        ),
                    description = "Postpone one currently due automation without executing its prompt.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "taskId",
                                required = true,
                                description = "Due task identifier",
                            ),
                            ToolArgumentSpec(
                                name = "delayMinutes",
                                description = "Positive minutes to postpone. Defaults to 15, max 10080.",
                            ),
                            ToolArgumentSpec(
                                name = "untilIso",
                                description = "Optional ISO-8601 instant to postpone until instead of delayMinutes.",
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
                    toolName = "tasks.snooze",
                    summary = "tasks.snooze requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.snooze", taskId = taskId)
            val snoozedAt = clock.instant()
            val snoozedUntil =
                try {
                    arguments.parseTaskSnoozeUntil(now = snoozedAt)
                } catch (error: IllegalArgumentException) {
                    return@Entry invalidTaskArguments(
                        toolName = "tasks.snooze",
                        summary = error.message ?: "tasks.snooze received invalid arguments.",
                    )
                }
            val dueAt =
                task.nextRunAt
                    ?.takeIf { nextRunAt -> !nextRunAt.isAfter(snoozedAt) }
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Task ${task.name} is not currently due.",
                        errorCode = "TASK_NOT_DUE",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TASK_NOT_DUE")
                                put("toolName", "tasks.snooze")
                                put("taskId", task.id)
                                put("enabled", task.enabled)
                                put("nowIso", snoozedAt.toString())
                                put("nextRunAtIso", task.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                            },
                    )
            if (!task.enabled) {
                return@Entry ToolExecutionResult.failure(
                    summary = "Task ${task.name} is disabled and cannot be snoozed as a due automation.",
                    errorCode = "TASK_NOT_DUE",
                    payload =
                        buildJsonObject {
                            put("errorCode", "TASK_NOT_DUE")
                            put("toolName", "tasks.snooze")
                            put("taskId", task.id)
                            put("enabled", false)
                            put("nowIso", snoozedAt.toString())
                            put("nextRunAtIso", dueAt.toString())
                        },
                )
            }
            val updatedTask =
                task.copy(
                    nextRunAt = snoozedUntil,
                    updatedAt = snoozedAt,
                )
            taskRepository.updateTask(updatedTask)
            schedulerCoordinator.scheduleTask(updatedTask.id)
            val reloadedTask = taskRepository.getTask(updatedTask.id) ?: updatedTask
            ToolExecutionResult.success(
                summary = "Snoozed due run for task ${reloadedTask.name}.",
                payload =
                    buildJsonObject {
                        put("snoozedAtIso", snoozedAt.toString())
                        put("previousNextRunAtIso", dueAt.toString())
                        put("snoozedUntilIso", snoozedUntil.toString())
                        put("snoozeDelaySeconds", Duration.between(snoozedAt, snoozedUntil).seconds)
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
                    name = "tasks.search",
                    aliases = listOf("task.search"),
                    description = "Search persisted automations by name or prompt text.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "query",
                                required = true,
                                description = "Task name or prompt text to search for.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum result count. Defaults to 20.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val query =
                arguments.optionalText("query")
                    ?: return@Entry invalidTaskArguments(
                        toolName = "tasks.search",
                        summary = "tasks.search requires a non-empty query.",
                        field = "query",
                    )
            val limit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = TASK_SEARCH_DEFAULT_LIMIT,
                )
            val tasks = taskRepository.searchTasks(query = query, limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (tasks.isEmpty()) {
                        "No tasks matched \"$query\"."
                    } else {
                        "Found ${tasks.size} task(s) matching \"$query\"."
                    },
                payload =
                    buildJsonObject {
                        put("query", query)
                        put("resultCount", tasks.size)
                        put(
                            "tasks",
                            buildJsonArray {
                                tasks.forEach { task ->
                                    add(task.toTaskSearchPayload())
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.stats",
                    aliases = listOf("task.stats", "automations.stats", "automation.stats"),
                    description = "Return aggregate scheduler and automation-run statistics without loading every task.",
                ),
        ) { _, _ ->
            val stats = taskRepository.getTaskStats(clock.instant())
            ToolExecutionResult.success(
                summary = "Loaded automation stats for ${stats.totalTaskCount} task(s) and ${stats.totalRunCount} run(s).",
                payload =
                    stats.toTaskStatsPayload(
                        minimumBackgroundIntervalMinutes =
                            schedulerCoordinator
                                .capabilities()
                                .minimumBackgroundInterval
                                .toMinutes(),
                    ),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.agenda",
                    aliases =
                        listOf(
                            "task.agenda",
                            "automations.agenda",
                            "automation.agenda",
                            "tasks.schedule.handoff",
                            "automations.handoff",
                        ),
                    description = "Prepare a compact automation agenda with due and upcoming tasks without prompt bodies.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum due and upcoming task count per section. Defaults to 10, max 50.",
                            ),
                            ToolArgumentSpec(
                                name = "includePromptSnippets",
                                description = "Set false to omit prompt snippets. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit agendaMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TASK_AGENDA_DEFAULT_LIMIT,
                    ).coerceIn(0, TASK_AGENDA_MAX_LIMIT)
            val includePromptSnippets = arguments.optionalBoolean("includePromptSnippets", defaultValue = true)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val now = clock.instant()
            val dueTasks = taskRepository.getEnabledTasksDueBefore(instant = now, limit = limit)
            val upcomingTasks = taskRepository.getFutureEnabledTasksAfter(instant = now, limit = limit)
            val agendaTasks = (dueTasks + upcomingTasks).distinctBy(Task::id)
            val targetSessions =
                agendaTasks
                    .mapNotNull { task -> task.targetSessionId }
                    .distinct()
                    .associateWith { sessionId -> sessionRepository.getSession(sessionId) }
            val diagnostics = schedulerCoordinator.diagnostics()
            val stats = taskRepository.getTaskStats(now)
            val minimumBackgroundIntervalMinutes =
                schedulerCoordinator
                    .capabilities()
                    .minimumBackgroundInterval
                    .toMinutes()
            val agendaMarkdown =
                if (includeMarkdown) {
                    stats.toTaskAgendaMarkdown(
                        dueTasks = dueTasks,
                        upcomingTasks = upcomingTasks,
                        limit = limit,
                        includePromptSnippets = includePromptSnippets,
                        minimumBackgroundIntervalMinutes = minimumBackgroundIntervalMinutes,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    when {
                        dueTasks.isEmpty() && upcomingTasks.isEmpty() -> "Prepared empty automation agenda."
                        dueTasks.isEmpty() -> "Prepared automation agenda with ${upcomingTasks.size} upcoming task(s)."
                        upcomingTasks.isEmpty() -> "Prepared automation agenda with ${dueTasks.size} due task(s)."
                        else -> "Prepared automation agenda with ${dueTasks.size} due and ${upcomingTasks.size} upcoming task(s)."
                    },
                payload =
                    buildJsonObject {
                        put("nowIso", now.toString())
                        put("limit", limit)
                        put("dueReturnedCount", dueTasks.size)
                        put("upcomingReturnedCount", upcomingTasks.size)
                        put("agendaTaskCount", agendaTasks.size)
                        put("includePromptSnippets", includePromptSnippets)
                        put("includeMarkdown", includeMarkdown)
                        put("promptBodiesIncluded", false)
                        put(
                            "stats",
                            stats.toTaskStatsPayload(
                                minimumBackgroundIntervalMinutes = minimumBackgroundIntervalMinutes,
                            ),
                        )
                        put(
                            "dueTasks",
                            buildJsonArray {
                                dueTasks.forEach { task ->
                                    add(
                                        task.toTaskAgendaPayload(
                                            now = now,
                                            targetSession = task.targetSessionId?.let { sessionId -> targetSessions[sessionId] },
                                            diagnostics = diagnostics,
                                            includePromptSnippet = includePromptSnippets,
                                        ),
                                    )
                                }
                            },
                        )
                        put(
                            "upcomingTasks",
                            buildJsonArray {
                                upcomingTasks.forEach { task ->
                                    add(
                                        task.toTaskAgendaPayload(
                                            now = now,
                                            targetSession = task.targetSessionId?.let { sessionId -> targetSessions[sessionId] },
                                            diagnostics = diagnostics,
                                            includePromptSnippet = includePromptSnippets,
                                        ),
                                    )
                                }
                            },
                        )
                        put("agendaMarkdown", agendaMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.doctor",
                    aliases =
                        listOf(
                            "task.doctor",
                            "tasks.check",
                            "task.check",
                            "automations.doctor",
                            "automation.doctor",
                            "automations.check",
                            "automation.check",
                        ),
                    description = "Return actionable automation diagnostics without task prompt bodies.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum diagnostic issues to include. Defaults to 20.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDisabled",
                                description = "Set false to omit disabled automations before diagnostics. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit doctorMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TASK_DOCTOR_DEFAULT_LIMIT,
                    ).coerceIn(0, TASK_DOCTOR_MAX_LIMIT)
            val includeDisabled = arguments.optionalBoolean("includeDisabled", defaultValue = true)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val now = clock.instant()
            val tasks = taskRepository.observeTasks().first()
            val candidates =
                if (includeDisabled) {
                    tasks
                } else {
                    tasks.filter { task -> task.enabled }
                }
            val targetSessions =
                candidates
                    .mapNotNull { task -> task.targetSessionId }
                    .distinct()
                    .associateWith { sessionId -> sessionRepository.getSession(sessionId) }
            val diagnostics = schedulerCoordinator.diagnostics()
            val issues =
                candidates.flatMap { task ->
                    task.toTaskDoctorIssues(
                        now = now,
                        diagnostics = diagnostics,
                        targetSession = task.targetSessionId?.let { sessionId -> targetSessions[sessionId] },
                    )
                }
            val includedIssues = issues.take(limit)
            val status = issues.toTaskDoctorStatus()
            val stats = taskRepository.getTaskStats(now)
            val minimumBackgroundIntervalMinutes =
                schedulerCoordinator
                    .capabilities()
                    .minimumBackgroundInterval
                    .toMinutes()
            val doctorMarkdown =
                if (includeMarkdown) {
                    includedIssues.toTaskDoctorMarkdown(
                        status = status,
                        totalTaskCount = tasks.size,
                        candidateTaskCount = candidates.size,
                        issueCount = issues.size,
                        limit = limit,
                        includeDisabled = includeDisabled,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    when {
                        issues.isEmpty() ->
                            "Automation doctor found no issues across ${candidates.size} candidate task(s)."
                        includedIssues.size == issues.size ->
                            "Automation doctor found ${issues.size} issue(s) across ${candidates.size} candidate task(s)."
                        else ->
                            "Automation doctor found ${issues.size} issue(s) and included ${includedIssues.size}."
                    },
                payload =
                    buildJsonObject {
                        put("status", status)
                        put("nowIso", now.toString())
                        put("taskCount", tasks.size)
                        put("candidateTaskCount", candidates.size)
                        put("issueCount", issues.size)
                        put("includedIssueCount", includedIssues.size)
                        put("omittedIssueCount", (issues.size - includedIssues.size).coerceAtLeast(0))
                        put("errorCount", issues.count { issue -> issue.severity == "Error" })
                        put("warningCount", issues.count { issue -> issue.severity == "Warning" })
                        put("limit", limit)
                        put("includeDisabled", includeDisabled)
                        put("includeMarkdown", includeMarkdown)
                        put("promptBodiesOmitted", true)
                        put(
                            "stats",
                            stats.toTaskStatsPayload(
                                minimumBackgroundIntervalMinutes = minimumBackgroundIntervalMinutes,
                            ),
                        )
                        put(
                            "issues",
                            buildJsonArray {
                                includedIssues.forEach { issue ->
                                    add(issue.toTaskDoctorPayload())
                                }
                            },
                        )
                        put("doctorMarkdown", doctorMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.due",
                    aliases =
                        listOf(
                            "task.due",
                            "tasks.overdue",
                            "task.overdue",
                            "automations.due",
                            "automation.due",
                        ),
                    description = "List enabled automations that are currently due, ordered by scheduled run time.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum task count. Defaults to 20, max 50.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TASK_DUE_DEFAULT_LIMIT,
                    ).coerceIn(0, TASK_DUE_MAX_LIMIT)
            val now = clock.instant()
            val tasks = taskRepository.getEnabledTasksDueBefore(instant = now, limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (tasks.isEmpty()) {
                        "No due enabled automations found."
                    } else {
                        "Loaded ${tasks.size} due enabled automation(s)."
                    },
                payload =
                    buildJsonObject {
                        put("nowIso", now.toString())
                        put("returnedCount", tasks.size)
                        put("taskCount", tasks.size)
                        put("dueTaskCount", tasks.size)
                        put(
                            "oldestDueAtIso",
                            tasks.firstOrNull()?.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                        )
                        put(
                            "newestDueAtIso",
                            tasks.lastOrNull()?.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                        )
                        put(
                            "tasks",
                            buildJsonArray {
                                tasks.forEach { task ->
                                    add(task.toDueTaskPayload(now = now))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.skip",
                    aliases =
                        listOf(
                            "task.skip",
                            "tasks.skip_due",
                            "task.skip_due",
                            "automations.skip",
                            "automation.skip",
                        ),
                    description = "Skip one currently due automation run without executing its prompt.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "taskId",
                                required = true,
                                description = "Due task identifier",
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
                    toolName = "tasks.skip",
                    summary = "tasks.skip requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.skip", taskId = taskId)
            val skippedAt = clock.instant()
            val dueAt =
                task.nextRunAt
                    ?.takeIf { nextRunAt -> !nextRunAt.isAfter(skippedAt) }
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Task ${task.name} is not currently due.",
                        errorCode = "TASK_NOT_DUE",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TASK_NOT_DUE")
                                put("toolName", "tasks.skip")
                                put("taskId", task.id)
                                put("enabled", task.enabled)
                                put("nowIso", skippedAt.toString())
                                put("nextRunAtIso", task.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                            },
                    )
            if (!task.enabled) {
                return@Entry ToolExecutionResult.failure(
                    summary = "Task ${task.name} is disabled and cannot be skipped as a due automation.",
                    errorCode = "TASK_NOT_DUE",
                    payload =
                        buildJsonObject {
                            put("errorCode", "TASK_NOT_DUE")
                            put("toolName", "tasks.skip")
                            put("taskId", task.id)
                            put("enabled", false)
                            put("nowIso", skippedAt.toString())
                            put("nextRunAtIso", dueAt.toString())
                        },
                )
            }
            val skippedRun =
                taskRepository
                    .recordRun(taskId = task.id, scheduledAt = dueAt)
                    .copy(
                        status = TaskRunStatus.Skipped,
                        startedAt = skippedAt,
                        finishedAt = skippedAt,
                        resultSummary = "Skipped by tasks.skip.",
                    )
            taskRepository.updateRun(skippedRun)
            val nextRunAt = schedulerCoordinator.taskPlanner.nextScheduledRun(task, skippedAt)
            val updatedTask =
                task.copy(
                    nextRunAt = nextRunAt,
                    lastRunAt = skippedAt,
                    failureCount = 0,
                    updatedAt = skippedAt,
                )
            taskRepository.updateTask(updatedTask)
            schedulerCoordinator.scheduleTask(updatedTask.id)
            val reloadedTask = taskRepository.getTask(updatedTask.id) ?: updatedTask
            ToolExecutionResult.success(
                summary = "Skipped due run for task ${reloadedTask.name}.",
                payload =
                    buildJsonObject {
                        put("skippedAtIso", skippedAt.toString())
                        put("previousNextRunAtIso", dueAt.toString())
                        put("nextRunAtIso", reloadedTask.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("run", skippedRun.toTaskRunHistoryPayload())
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
                    name = "tasks.next",
                    aliases =
                        listOf(
                            "task.next",
                            "tasks.upcoming",
                            "task.upcoming",
                            "automations.next",
                            "automation.next",
                        ),
                    description = "List upcoming enabled automations ordered by next scheduled run.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum task count. Defaults to 20, max 50.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TASK_UPCOMING_DEFAULT_LIMIT,
                    ).coerceIn(0, TASK_UPCOMING_MAX_LIMIT)
            val now = clock.instant()
            val tasks = taskRepository.getUpcomingEnabledTasks(limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (tasks.isEmpty()) {
                        "No upcoming enabled automations found."
                    } else {
                        "Loaded ${tasks.size} upcoming enabled automation(s)."
                    },
                payload =
                    buildJsonObject {
                        put("nowIso", now.toString())
                        put("returnedCount", tasks.size)
                        put("taskCount", tasks.size)
                        put("dueTaskCount", tasks.count { task -> task.nextRunAt?.isAfter(now) == false })
                        put("soonestRunAtIso", tasks.firstOrNull()?.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put(
                            "tasks",
                            buildJsonArray {
                                tasks.forEach { task ->
                                    add(task.toUpcomingTaskPayload(now = now))
                                }
                            },
                        )
                    },
            )
        },
        *taskRunToolEntries(
            taskRepository = taskRepository,
            sessionRepository = sessionRepository,
            schedulerCoordinator = schedulerCoordinator,
            clock = clock,
        ).toTypedArray(),
        ToolRegistry.Entry(
            descriptor = taskDuplicateExampleDescriptor(),
        ) { _, arguments ->
            val requestedMode =
                arguments.optionalText("copyMode")
                    ?: arguments.optionalText("mode")
            val selectedModes =
                taskDuplicateExampleCopyModes(requestedMode)
                    ?: return@Entry invalidTaskArguments(
                        toolName = "tasks.duplicate.example",
                        summary =
                            "tasks.duplicate.example supports copyMode disabled, enabled, or all.",
                        field = "copyMode",
                    )
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val examples =
                buildJsonArray {
                    selectedModes.forEach { copyMode ->
                        add(taskDuplicateExamplePayload(copyMode = copyMode))
                    }
                }
            val exampleMarkdown =
                if (includeMarkdown) {
                    taskDuplicateExampleMarkdown(
                        requestedMode = requestedMode,
                        examples = examples,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary = "Prepared ${selectedModes.size} task duplicate example(s) without duplicating automation.",
                payload =
                    buildJsonObject {
                        put("generatedAtIso", clock.instant().toString())
                        put("requestedCopyMode", requestedMode?.let(::JsonPrimitive) ?: JsonNull)
                        put("copyModeCount", selectedModes.size)
                        put("supportedCopyModes", TASK_DUPLICATE_EXAMPLE_COPY_MODES.toToolStringArrayPayload())
                        put("defaultEnabled", false)
                        put("recommendedEnabled", false)
                        put("includeMarkdown", includeMarkdown)
                        put("exampleOnly", true)
                        put("executesTaskDuplicate", false)
                        put("duplicatesAutomation", false)
                        put("createsAutomation", false)
                        put("schedulesWork", false)
                        put("mutatesTasks", false)
                        put("sourceTaskResolved", false)
                        put("sourcePromptBodyIncluded", false)
                        put("copiedPromptBodyIncluded", false)
                        put("secretValuesIncluded", false)
                        put("providerMetadataIncluded", false)
                        put("runHistoryIncluded", false)
                        put(
                            "suggestedTools",
                            listOf(
                                "tasks.list",
                                "tasks.get",
                                "tasks.duplicate",
                                "tasks.agenda",
                            ).toToolStringArrayPayload(),
                        )
                        put("examples", examples)
                        put("exampleMarkdown", exampleMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor = taskDuplicateDescriptor(),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.duplicate",
                    summary = "tasks.duplicate requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val sourceTask =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.duplicate", taskId = taskId)
            val copyName = arguments.optionalText("name") ?: "Copy of ${sourceTask.name}"
            val enabled = arguments.optionalBoolean("enabled", defaultValue = false)
            val createdTask =
                taskRepository.createTask(
                    name = copyName,
                    prompt = sourceTask.prompt,
                    schedule = sourceTask.schedule,
                    executionMode = sourceTask.executionMode,
                    targetSessionId = sourceTask.targetSessionId,
                    precise = sourceTask.precise,
                    maxRetries = sourceTask.maxRetries,
                )
            val finalTask =
                if (enabled) {
                    createdTask
                } else {
                    createdTask.copy(
                        enabled = false,
                        updatedAt = clock.instant(),
                    )
                }
            if (finalTask != createdTask) {
                taskRepository.updateTask(finalTask)
            }
            if (finalTask.enabled) {
                schedulerCoordinator.scheduleTask(finalTask.id)
            }
            val reloadedTask = taskRepository.getTask(finalTask.id) ?: finalTask
            ToolExecutionResult.success(
                summary =
                    if (reloadedTask.enabled) {
                        "Duplicated and enabled task ${sourceTask.name} as ${reloadedTask.name}."
                    } else {
                        "Duplicated task ${sourceTask.name} as disabled copy ${reloadedTask.name}."
                    },
                payload =
                    buildJsonObject {
                        put("sourceTaskId", sourceTask.id)
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
            descriptor = taskCreateExampleDescriptor(),
        ) { _, arguments ->
            val requestedKind =
                arguments.optionalText("scheduleKind")
                    ?: arguments.optionalText("kind")
            val selectedKinds =
                taskCreateExampleScheduleKinds(requestedKind)
                    ?: return@Entry invalidTaskArguments(
                        toolName = "tasks.create.example",
                        summary =
                            "tasks.create.example supports scheduleKind once, interval, cron, or all.",
                        field = "scheduleKind",
                    )
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val generatedAt = clock.instant()
            val examples =
                buildJsonArray {
                    selectedKinds.forEach { scheduleKind ->
                        add(taskCreateExamplePayload(scheduleKind = scheduleKind, now = generatedAt))
                    }
                }
            val exampleMarkdown =
                if (includeMarkdown) {
                    taskCreateExampleMarkdown(
                        requestedKind = requestedKind,
                        examples = examples,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary = "Prepared ${selectedKinds.size} task creation example(s) without creating automation.",
                payload =
                    buildJsonObject {
                        put("generatedAtIso", generatedAt.toString())
                        put("requestedScheduleKind", requestedKind?.let(::JsonPrimitive) ?: JsonNull)
                        put("scheduleKindCount", selectedKinds.size)
                        put("supportedScheduleKinds", TASK_CREATE_EXAMPLE_SCHEDULE_KINDS.toToolStringArrayPayload())
                        put(
                            "supportedExecutionModes",
                            listOf("MAIN_SESSION", "ISOLATED_SESSION").toToolStringArrayPayload(),
                        )
                        put("supportedTargetSessionAliases", listOf("main", "current").toToolStringArrayPayload())
                        put("defaultExecutionMode", "MAIN_SESSION")
                        put("defaultTargetSessionAlias", "main")
                        put("includeMarkdown", includeMarkdown)
                        put("exampleOnly", true)
                        put("executesTaskCreation", false)
                        put("createsAutomation", false)
                        put("schedulesWork", false)
                        put("mutatesTasks", false)
                        put("examplePromptIncluded", true)
                        put("userPromptBodyIncluded", false)
                        put("secretValuesIncluded", false)
                        put("providerMetadataIncluded", false)
                        put("runHistoryIncluded", false)
                        put(
                            "suggestedTools",
                            listOf(
                                "tasks.preview",
                                "tasks.preview.occurrences",
                                "tasks.create",
                                "tasks.agenda",
                            ).toToolStringArrayPayload(),
                        )
                        put("examples", examples)
                        put("exampleMarkdown", exampleMarkdown?.let(::JsonPrimitive) ?: JsonNull)
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
                        now = clock.instant(),
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
            descriptor = taskUpdateExampleDescriptor(),
        ) { _, arguments ->
            val requestedKind =
                arguments.optionalText("patchKind")
                    ?: arguments.optionalText("scheduleKind")
                    ?: arguments.optionalText("kind")
            val selectedKinds =
                taskUpdateExamplePatchKinds(requestedKind)
                    ?: return@Entry invalidTaskArguments(
                        toolName = "tasks.update.example",
                        summary =
                            "tasks.update.example supports patchKind metadata, once, interval, cron, or all.",
                        field = "patchKind",
                    )
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val generatedAt = clock.instant()
            val examples =
                buildJsonArray {
                    selectedKinds.forEach { patchKind ->
                        add(taskUpdateExamplePayload(patchKind = patchKind, now = generatedAt))
                    }
                }
            val exampleMarkdown =
                if (includeMarkdown) {
                    taskUpdateExampleMarkdown(
                        requestedKind = requestedKind,
                        examples = examples,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary = "Prepared ${selectedKinds.size} task update example(s) without updating automation.",
                payload =
                    buildJsonObject {
                        put("generatedAtIso", generatedAt.toString())
                        put("requestedPatchKind", requestedKind?.let(::JsonPrimitive) ?: JsonNull)
                        put("patchKindCount", selectedKinds.size)
                        put("supportedPatchKinds", TASK_UPDATE_EXAMPLE_PATCH_KINDS.toToolStringArrayPayload())
                        put("supportedScheduleKinds", TASK_CREATE_EXAMPLE_SCHEDULE_KINDS.toToolStringArrayPayload())
                        put(
                            "supportedExecutionModes",
                            listOf("MAIN_SESSION", "ISOLATED_SESSION").toToolStringArrayPayload(),
                        )
                        put("supportedTargetSessionAliases", listOf("main", "current").toToolStringArrayPayload())
                        put("includeMarkdown", includeMarkdown)
                        put("exampleOnly", true)
                        put("executesTaskUpdate", false)
                        put("updatesAutomation", false)
                        put("schedulesWork", false)
                        put("mutatesTasks", false)
                        put("examplePromptIncluded", selectedKinds.contains("metadata"))
                        put("userPromptBodyIncluded", false)
                        put("secretValuesIncluded", false)
                        put("providerMetadataIncluded", false)
                        put("runHistoryIncluded", false)
                        put(
                            "suggestedTools",
                            listOf(
                                "tasks.list",
                                "tasks.get",
                                "tasks.preview",
                                "tasks.update",
                                "tasks.agenda",
                            ).toToolStringArrayPayload(),
                        )
                        put("examples", examples)
                        put("exampleMarkdown", exampleMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
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
                        now = clock.instant(),
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
                    updatedAt = clock.instant(),
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
                    updatedAt = clock.instant(),
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
            val queuedAt = clock.instant()
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
