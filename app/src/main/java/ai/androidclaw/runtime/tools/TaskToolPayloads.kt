package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.Session
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.MAX_SAFE_DURATION_MINUTES
import ai.androidclaw.runtime.scheduler.NextRunCalculator
import ai.androidclaw.runtime.scheduler.SchedulerDiagnostics
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.runtime.scheduler.precisionMode
import ai.androidclaw.runtime.scheduler.schedulingDecision
import ai.androidclaw.runtime.scheduler.userVisiblePreciseWarnings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

internal const val TASK_AGENDA_DEFAULT_LIMIT = 10
internal const val TASK_AGENDA_MAX_LIMIT = 50
internal const val TASK_DUE_DEFAULT_LIMIT = 20
internal const val TASK_DUE_MAX_LIMIT = 50
internal const val TASK_DOCTOR_DEFAULT_LIMIT = 20
internal const val TASK_DOCTOR_MAX_LIMIT = 50
internal const val TASK_DOCTOR_TEXT_MAX_CHARS = 500
internal const val TASK_EXPORT_FORMAT = "androidclaw.tasks.export.v1"
internal const val TASK_EXPORT_VERSION = 1
internal const val TASK_EXPORT_DEFAULT_LIMIT = 50
internal const val TASK_EXPORT_MAX_LIMIT = 100
internal const val TASK_HANDOFF_DEFAULT_RUN_LIMIT = 5
internal const val TASK_HANDOFF_MAX_RUN_LIMIT = 20
internal const val TASK_IMPORT_FORMAT = "androidclaw.tasks.import.v1"
internal const val TASK_IMPORT_VERSION = 1
internal const val TASK_IMPORT_DEFAULT_LIMIT = 50
internal const val TASK_IMPORT_MAX_LIMIT = 100
internal const val TASK_OCCURRENCES_DEFAULT_LIMIT = 5
internal const val TASK_OCCURRENCES_MAX_LIMIT = 20
internal const val TASK_RUN_HISTORY_DEFAULT_LIMIT = 10
internal const val TASK_SEARCH_DEFAULT_LIMIT = 20
internal const val TASK_SNOOZE_DEFAULT_DELAY_MINUTES = 15L
internal const val TASK_SNOOZE_MAX_DELAY_MINUTES = 10_080L
internal const val TASK_TIMELINE_DEFAULT_LIMIT = 20
internal const val TASK_TIMELINE_DEFAULT_PER_TASK_LIMIT = 5
internal const val TASK_TIMELINE_MAX_LIMIT = 100
internal const val TASK_TIMELINE_MAX_PER_TASK_LIMIT = 20
internal const val TASK_UPCOMING_DEFAULT_LIMIT = 20
internal const val TASK_UPCOMING_MAX_LIMIT = 50

internal data class TaskDoctorIssue(
    val id: String,
    val severity: String,
    val code: String,
    val taskId: String,
    val taskName: String,
    val enabled: Boolean,
    val scheduleKind: String,
    val executionMode: String,
    val targetSessionId: String?,
    val nextRunAt: Instant?,
    val lastRunAt: Instant?,
    val failureCount: Int,
    val maxRetries: Int,
    val summary: String,
    val action: String,
    val secondsOverdue: Long? = null,
    val detail: String? = null,
)

internal data class TaskImportCandidate(
    val sourceIndex: Int,
    val sourceTaskId: String?,
    val name: String,
    val prompt: String,
    val schedule: TaskSchedule,
    val executionMode: TaskExecutionMode,
    val sourceTargetSessionId: String?,
    val sourceEnabled: Boolean,
    val precise: Boolean,
    val maxRetries: Int,
)

internal data class TaskImportedItem(
    val candidate: TaskImportCandidate,
    val task: Task,
    val importedTargetSessionId: String?,
    val importedEnabled: Boolean,
)

internal data class TaskImportSkippedEntry(
    val sourceIndex: Int,
    val code: String,
    val summary: String,
)

internal sealed interface TaskImportEntriesParseResult {
    data class Success(
        val entries: JsonArray,
    ) : TaskImportEntriesParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : TaskImportEntriesParseResult
}

internal sealed interface TaskImportCandidateParseResult {
    data class Candidate(
        val candidate: TaskImportCandidate,
    ) : TaskImportCandidateParseResult

    data class Skipped(
        val skipped: TaskImportSkippedEntry,
    ) : TaskImportCandidateParseResult
}

internal fun Task.toTaskExportPayload(
    includePrompt: Boolean,
    targetSession: Session?,
    includeTargetSessionMetadata: Boolean,
): JsonObject =
    buildJsonObject {
        put("taskId", id)
        put("sourceTaskId", id)
        put("name", name)
        put("enabled", enabled)
        put("scheduleKind", schedule.toTaskSearchKind())
        put("schedule", schedule.toPayload())
        put("executionMode", executionMode.name)
        put("targetSessionId", targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("targetSessionMetadataIncluded", includeTargetSessionMetadata)
        put("targetSessionMissing", includeTargetSessionMetadata && targetSessionId != null && targetSession == null)
        put("targetSessionArchived", if (includeTargetSessionMetadata) targetSession?.archived?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
        put(
            "targetSession",
            if (includeTargetSessionMetadata) {
                targetSession?.let { session ->
                    buildJsonObject {
                        put("id", session.id)
                        put("title", session.title)
                        put("isMain", session.isMain)
                        put("archived", session.archived)
                    }
                } ?: JsonNull
            } else {
                JsonNull
            },
        )
        put("preciseRequested", precise)
        put("nextRunAtIso", nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("lastRunAtIso", lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("failureCount", failureCount)
        put("maxRetries", maxRetries)
        put("createdAtIso", createdAt.toString())
        put("updatedAtIso", updatedAt.toString())
        put("prompt", if (includePrompt) JsonPrimitive(prompt) else JsonNull)
        put("promptLength", prompt.length)
        put("promptIncluded", includePrompt)
        put("promptBodyIncluded", includePrompt)
        put("fullPromptBodyIncluded", includePrompt)
        put("runHistoryIncluded", false)
        put("providerMetaIncluded", false)
    }

internal fun List<Task>.toTaskExportScheduleStatsPayload(): JsonArray =
    buildJsonArray {
        groupBy { task -> task.schedule.toTaskSearchKind() }
            .toSortedMap()
            .forEach { (scheduleKind, tasks) ->
                add(
                    buildJsonObject {
                        put("scheduleKind", scheduleKind)
                        put("taskCount", tasks.size)
                    },
                )
            }
    }

internal fun List<Task>.toTaskExportExecutionModeStatsPayload(): JsonArray =
    buildJsonArray {
        groupBy { task -> task.executionMode.name }
            .toSortedMap()
            .forEach { (executionMode, tasks) ->
                add(
                    buildJsonObject {
                        put("executionMode", executionMode)
                        put("taskCount", tasks.size)
                    },
                )
            }
    }

internal fun List<Task>.toTaskExportMarkdown(
    totalTaskCount: Int,
    candidateTaskCount: Int,
    limit: Int,
    includeDisabled: Boolean,
    includePrompts: Boolean,
): String {
    val exportedTasks = this
    return buildString {
        appendLine("# Automation export")
        appendLine()
        appendLine("- Format: $TASK_EXPORT_FORMAT")
        appendLine("- Version: $TASK_EXPORT_VERSION")
        appendLine("- Total automations: $totalTaskCount")
        appendLine("- Candidate automations after filters: $candidateTaskCount")
        appendLine("- Automations exported: ${exportedTasks.size} of up to $limit")
        appendLine("- Disabled automations included: $includeDisabled")
        appendLine("- Prompt bodies included: $includePrompts")
        appendLine("- Run history included: false")
        appendLine()
        appendLine("## Automations")
        if (exportedTasks.isEmpty()) {
            appendLine("_No automations exported._")
        } else {
            exportedTasks.forEach { task ->
                append("- `")
                append(task.id.toHandoffLine())
                append("` ")
                append(task.name.toHandoffLine())
                append(" enabled=")
                append(task.enabled)
                append(" schedule=")
                append(task.schedule.toTaskSearchKind())
                append(" mode=")
                append(task.executionMode.name)
                append(" next=")
                append(task.nextRunAt ?: "none")
                append(" prompt=")
                if (includePrompts) {
                    append(task.prompt.toHandoffLine())
                } else {
                    append("_omitted_")
                }
                appendLine()
            }
        }
    }
}

internal fun JsonObject.taskImportEntries(): TaskImportEntriesParseResult {
    val directEntries = this["tasks"]
    val exportEntries = (this["export"] as? JsonObject)?.get("tasks")
    val payloadEntries = (this["payload"] as? JsonObject)?.get("tasks")
    val entries =
        directEntries ?: exportEntries ?: payloadEntries ?: return TaskImportEntriesParseResult.Failure(
            missingTaskImportEntriesResult(),
        )
    return (entries as? JsonArray)?.let(TaskImportEntriesParseResult::Success)
        ?: TaskImportEntriesParseResult.Failure(invalidTaskImportEntriesResult())
}

internal fun JsonElement.toTaskImportCandidate(sourceIndex: Int): TaskImportCandidateParseResult {
    val objectValue =
        this as? JsonObject ?: return taskImportSkipped(
            sourceIndex = sourceIndex,
            code = "tasks.import.invalid_entry",
            summary = "Import entry must be a task object.",
        )
    val name =
        objectValue.optionalText("name")
            ?: return taskImportSkipped(
                sourceIndex = sourceIndex,
                code = "tasks.import.invalid_missing_name",
                summary = "Import entry skipped because name is missing or blank.",
            )
    val prompt =
        objectValue.optionalRawText("prompt")
            ?: objectValue.optionalRawText("text")
            ?: return taskImportSkipped(
                sourceIndex = sourceIndex,
                code = "tasks.import.invalid_missing_prompt",
                summary = "Import entry skipped because prompt is missing or blank.",
            )
    val schedule =
        objectValue.toTaskImportSchedule()
            ?: return taskImportSkipped(
                sourceIndex = sourceIndex,
                code = "tasks.import.invalid_schedule",
                summary = "Import entry skipped because schedule is missing or invalid.",
            )
    val executionMode =
        objectValue.toTaskImportExecutionMode()
            ?: return taskImportSkipped(
                sourceIndex = sourceIndex,
                code = "tasks.import.invalid_execution_mode",
                summary = "Import entry skipped because executionMode is unsupported.",
            )
    return TaskImportCandidateParseResult.Candidate(
        TaskImportCandidate(
            sourceIndex = sourceIndex,
            sourceTaskId =
                objectValue.optionalMessageReferenceId("sourceTaskId")
                    ?: objectValue.optionalMessageReferenceId("taskId")
                    ?: objectValue.optionalMessageReferenceId("id"),
            name = name,
            prompt = prompt,
            schedule = schedule,
            executionMode = executionMode,
            sourceTargetSessionId = objectValue.optionalMessageReferenceId("targetSessionId"),
            sourceEnabled = objectValue.optionalBoolean("enabled", defaultValue = true),
            precise =
                objectValue.optionalBoolean(
                    field = "preciseRequested",
                    defaultValue = objectValue.optionalBoolean("precise", defaultValue = false),
                ),
            maxRetries = objectValue.optionalInt("maxRetries", defaultValue = 3).coerceAtLeast(0),
        ),
    )
}

internal fun JsonObject.toTaskImportSchedule(): TaskSchedule? {
    val scheduleObject = this["schedule"] as? JsonObject ?: return null
    val kind =
        scheduleObject.optionalText("kind")
            ?: optionalText("scheduleKind")
            ?: return null
    return when (kind.lowercase().replace("_", "-")) {
        "once" -> {
            val at = scheduleObject.optionalText("atIso") ?: optionalText("atIso") ?: return null
            runCatching {
                TaskSchedule.Once(Instant.parse(at))
            }.getOrNull()
        }
        "interval" -> {
            val anchorAt = scheduleObject.optionalText("anchorAtIso") ?: optionalText("anchorAtIso") ?: return null
            val repeatEveryMinutes =
                scheduleObject
                    .optionalText("repeatEveryMinutes")
                    ?.toLongOrNull()
                    ?: optionalText("repeatEveryMinutes")?.toLongOrNull()
                    ?: return null
            if (repeatEveryMinutes <= 0L || repeatEveryMinutes > MAX_SAFE_DURATION_MINUTES) {
                return null
            }
            runCatching {
                TaskSchedule.Interval(
                    anchorAt = Instant.parse(anchorAt),
                    repeatEvery = Duration.ofMinutes(repeatEveryMinutes),
                )
            }.getOrNull()
        }
        "cron" -> {
            val expression = scheduleObject.optionalText("cronExpression") ?: optionalText("cronExpression") ?: return null
            val timezone = scheduleObject.optionalText("timezone") ?: optionalText("timezone") ?: return null
            runCatching {
                TaskSchedule.Cron(
                    expression = CronExpression.parse(expression),
                    zoneId = ZoneId.of(timezone),
                )
            }.getOrNull()
        }
        else -> null
    }
}

internal fun JsonObject.toTaskImportExecutionMode(): TaskExecutionMode? {
    val rawValue = optionalText("executionMode") ?: return TaskExecutionMode.MainSession
    return when (rawValue.replace("-", "_").uppercase()) {
        "MAIN_SESSION", "MAINSESSION", "MAIN" -> TaskExecutionMode.MainSession
        "ISOLATED_SESSION", "ISOLATEDSESSION", "ISOLATED" -> TaskExecutionMode.IsolatedSession
        else -> null
    }
}

internal fun taskImportSkipped(
    sourceIndex: Int,
    code: String,
    summary: String,
): TaskImportCandidateParseResult.Skipped =
    TaskImportCandidateParseResult.Skipped(
        TaskImportSkippedEntry(
            sourceIndex = sourceIndex,
            code = code,
            summary = summary,
        ),
    )

internal fun TaskImportCandidate.importedEnabled(enableImported: Boolean): Boolean = enableImported && sourceEnabled

internal fun TaskImportCandidate.importTargetSessionId(
    preserveTargetSessionIds: Boolean,
    targetSessionsBySourceId: Map<String, Session?>,
): String? {
    val sourceSessionId = sourceTargetSessionId ?: return null
    if (!preserveTargetSessionIds) {
        return null
    }
    val targetSession = targetSessionsBySourceId[sourceSessionId] ?: return null
    return if (targetSession.archived) null else sourceSessionId
}

internal fun TaskImportCandidate.targetSessionPreserved(
    preserveTargetSessionIds: Boolean,
    targetSessionsBySourceId: Map<String, Session?>,
): Boolean = importTargetSessionId(preserveTargetSessionIds, targetSessionsBySourceId) != null

internal fun TaskImportCandidate.targetSessionDropped(
    preserveTargetSessionIds: Boolean,
    targetSessionsBySourceId: Map<String, Session?>,
): Boolean = sourceTargetSessionId != null && importTargetSessionId(preserveTargetSessionIds, targetSessionsBySourceId) == null

internal fun missingTaskImportConfirmationResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Pass confirm=CONFIRM to import automations, or dryRun=true to preview without writing.",
        errorCode = "MISSING_TASK_IMPORT_CONFIRMATION",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_TASK_IMPORT_CONFIRMATION")
                put("field", "confirm")
            },
    )

internal fun missingTaskImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide a tasks array or an export object containing tasks to import.",
        errorCode = "MISSING_TASK_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_TASK_IMPORT_ENTRIES")
                put("field", "tasks")
            },
    )

internal fun invalidTaskImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Task import entries must be an array.",
        errorCode = "INVALID_TASK_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_TASK_IMPORT_ENTRIES")
                put("field", "tasks")
            },
    )

internal fun TaskImportCandidate.toTaskImportCandidatePayload(
    includePrompt: Boolean,
    enableImported: Boolean,
    preserveTargetSessionIds: Boolean,
    targetSessionsBySourceId: Map<String, Session?>,
): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("sourceTaskId", sourceTaskId?.let(::JsonPrimitive) ?: JsonNull)
        put("name", name)
        put("sourceEnabled", sourceEnabled)
        put("importedEnabled", importedEnabled(enableImported))
        put("scheduleKind", schedule.toTaskSearchKind())
        put("schedule", schedule.toPayload())
        put("executionMode", executionMode.name)
        put("sourceTargetSessionId", sourceTargetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("targetSessionId", importTargetSessionId(preserveTargetSessionIds, targetSessionsBySourceId)?.let(::JsonPrimitive) ?: JsonNull)
        put("targetSessionPreserved", targetSessionPreserved(preserveTargetSessionIds, targetSessionsBySourceId))
        put("targetSessionDropped", targetSessionDropped(preserveTargetSessionIds, targetSessionsBySourceId))
        put("preciseRequested", precise)
        put("maxRetries", maxRetries)
        put("prompt", if (includePrompt) JsonPrimitive(prompt) else JsonNull)
        put("promptLength", prompt.length)
        put("promptIncluded", includePrompt)
        put("promptBodyIncluded", includePrompt)
        put("fullPromptBodyIncluded", includePrompt)
        put("runHistoryImported", false)
        put("runHistoryIncluded", false)
        put("providerMetaImported", false)
        put("providerMetaIncluded", false)
    }

internal fun TaskImportedItem.toTaskImportedPayload(includePrompt: Boolean): JsonObject =
    buildJsonObject {
        put("sourceIndex", candidate.sourceIndex)
        put("sourceTaskId", candidate.sourceTaskId?.let(::JsonPrimitive) ?: JsonNull)
        put("newTaskId", task.id)
        put("name", task.name)
        put("sourceEnabled", candidate.sourceEnabled)
        put("enabled", task.enabled)
        put("importedEnabled", importedEnabled)
        put("scheduleKind", task.schedule.toTaskSearchKind())
        put("schedule", task.schedule.toPayload())
        put("executionMode", task.executionMode.name)
        put("sourceTargetSessionId", candidate.sourceTargetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("targetSessionId", importedTargetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("preciseRequested", task.precise)
        put("nextRunAtIso", task.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("failureCount", task.failureCount)
        put("maxRetries", task.maxRetries)
        put("createdAtIso", task.createdAt.toString())
        put("updatedAtIso", task.updatedAt.toString())
        put("prompt", if (includePrompt) JsonPrimitive(task.prompt) else JsonNull)
        put("promptLength", task.prompt.length)
        put("promptIncluded", includePrompt)
        put("promptBodyIncluded", includePrompt)
        put("fullPromptBodyIncluded", includePrompt)
        put("runHistoryImported", false)
        put("runHistoryIncluded", false)
        put("providerMetaImported", false)
        put("providerMetaIncluded", false)
    }

internal fun TaskImportSkippedEntry.toTaskImportSkippedPayload(): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("code", code)
        put("summary", summary)
    }

internal fun TaskRepository.TaskStats.toTaskStatsPayload(minimumBackgroundIntervalMinutes: Long): JsonObject =
    buildJsonObject {
        put("supportsOnce", true)
        put("supportsInterval", true)
        put("supportsCron", true)
        put("minimumBackgroundIntervalMinutes", minimumBackgroundIntervalMinutes)
        put("taskCount", totalTaskCount)
        put("enabledTaskCount", enabledTaskCount)
        put("disabledTaskCount", disabledTaskCount)
        put("scheduledTaskCount", scheduledTaskCount)
        put("dueTaskCount", dueTaskCount)
        put("nextEnabledRunAtIso", nextEnabledRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("newestTaskUpdatedAtIso", newestTaskUpdatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("runCount", totalRunCount)
        put("oldestRunScheduledAtIso", oldestRunScheduledAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("newestRunScheduledAtIso", newestRunScheduledAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put(
            "scheduleKindStats",
            buildJsonArray {
                scheduleKindStats.forEach { stats ->
                    add(
                        buildJsonObject {
                            put("scheduleKind", stats.scheduleKind)
                            put("taskCount", stats.taskCount)
                        },
                    )
                }
            },
        )
        put(
            "executionModeStats",
            buildJsonArray {
                executionModeStats.forEach { stats ->
                    add(
                        buildJsonObject {
                            put("executionMode", stats.executionMode.name)
                            put("taskCount", stats.taskCount)
                        },
                    )
                }
            },
        )
        put(
            "runStatusStats",
            buildJsonArray {
                runStatusStats.forEach { stats ->
                    add(
                        buildJsonObject {
                            put("status", stats.status.name)
                            put("runCount", stats.runCount)
                            put("oldestScheduledAtIso", stats.oldestScheduledAt.toString())
                            put("newestScheduledAtIso", stats.newestScheduledAt.toString())
                        },
                    )
                }
            },
        )
    }

internal fun Task.toTaskDoctorIssues(
    now: Instant,
    diagnostics: SchedulerDiagnostics,
    targetSession: Session?,
): List<TaskDoctorIssue> =
    buildList {
        fun addIssue(
            severity: String,
            code: String,
            summary: String,
            action: String,
            secondsOverdue: Long? = null,
            detail: String? = null,
        ) {
            add(
                TaskDoctorIssue(
                    id = "$id:$code",
                    severity = severity,
                    code = code,
                    taskId = id,
                    taskName = name,
                    enabled = enabled,
                    scheduleKind = schedule.toTaskSearchKind(),
                    executionMode = executionMode.name,
                    targetSessionId = targetSessionId,
                    nextRunAt = nextRunAt,
                    lastRunAt = lastRunAt,
                    failureCount = failureCount,
                    maxRetries = maxRetries,
                    summary = summary.toTaskDoctorText(),
                    action = action.toTaskDoctorText(),
                    secondsOverdue = secondsOverdue,
                    detail = detail?.toTaskDoctorText(),
                ),
            )
        }

        if (prompt.isBlank()) {
            addIssue(
                severity = "Error",
                code = "task.prompt.empty",
                summary = "Automation $name has an empty prompt and cannot produce useful work.",
                action = "Run tasks.update with a non-empty prompt or delete this automation.",
            )
        }
        if (!enabled) {
            addIssue(
                severity = "Warning",
                code = "task.disabled",
                summary = "Automation $name is disabled and will not be scheduled.",
                action = "Run tasks.enable if this automation should resume, or delete it if it is obsolete.",
            )
        } else {
            val nextRun = nextRunAt
            when {
                nextRun == null ->
                    addIssue(
                        severity = "Warning",
                        code = "task.enabled.unscheduled",
                        summary = "Enabled automation $name has no next scheduled run.",
                        action = "Run tasks.reschedule, update the schedule, disable it, or delete it if complete.",
                    )
                !nextRun.isAfter(now) ->
                    addIssue(
                        severity = "Warning",
                        code = "task.due",
                        summary = "Automation $name is due and waiting to run.",
                        action = "Let WorkManager run it, run tasks.run_now, or use tasks.snooze/tasks.skip for a due automation.",
                        secondsOverdue = Duration.between(nextRun, now).seconds.coerceAtLeast(0),
                    )
            }
        }
        when {
            failureCount > maxRetries ->
                addIssue(
                    severity = "Error",
                    code = "task.retry.exhausted",
                    summary = "Automation $name has exhausted its retry budget.",
                    action = "Inspect recent task runs, fix the failing provider/tool cause, then run tasks.reschedule or tasks.run_now.",
                )
            failureCount > 0 ->
                addIssue(
                    severity = "Warning",
                    code = "task.failures.active",
                    summary = "Automation $name has $failureCount active failure(s) before retry recovery.",
                    action = "Inspect task run history and provider/tool health before relying on this automation.",
                )
        }
        targetSessionId?.let { sessionId ->
            when {
                targetSession == null ->
                    addIssue(
                        severity = "Warning",
                        code = "task.target_session.missing",
                        summary = "Automation $name targets a missing session.",
                        action = "Update targetSessionId or allow execution to fall back to the main session intentionally.",
                        detail = "targetSessionId=$sessionId",
                    )
                targetSession.archived ->
                    addIssue(
                        severity = "Warning",
                        code = "task.target_session.archived",
                        summary = "Automation $name targets archived session ${targetSession.title}.",
                        action = "Unarchive the target session or update the automation target.",
                        detail = "targetSessionId=$sessionId",
                    )
            }
        }
        val preciseWarnings = userVisiblePreciseWarnings(diagnostics)
        if (preciseWarnings.isNotEmpty()) {
            val decision = schedulingDecision(diagnostics)
            addIssue(
                severity = "Warning",
                code = "task.precision.warning",
                summary = "Automation $name requested precise scheduling but device/runtime capabilities may degrade it.",
                action = "Grant exact alarm and notification visibility permissions, or set precise=false for approximate scheduling.",
                detail =
                    buildString {
                        decision.degradedReason?.let { reason ->
                            append(reason)
                            append(" ")
                        }
                        append(preciseWarnings.joinToString("; "))
                    },
            )
        }
    }

internal fun List<TaskDoctorIssue>.toTaskDoctorStatus(): String =
    when {
        any { issue -> issue.severity == "Error" } -> "ERROR"
        any { issue -> issue.severity == "Warning" } -> "WARN"
        else -> "OK"
    }

internal fun TaskDoctorIssue.toTaskDoctorPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("severity", severity)
        put("code", code)
        put("taskId", taskId)
        put("taskName", taskName)
        put("enabled", enabled)
        put("scheduleKind", scheduleKind)
        put("executionMode", executionMode)
        put("targetSessionId", targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("nextRunAtIso", nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("lastRunAtIso", lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("failureCount", failureCount)
        put("maxRetries", maxRetries)
        put("summary", summary)
        put("action", action)
        put("secondsOverdue", secondsOverdue?.let(::JsonPrimitive) ?: JsonNull)
        put("detail", detail?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun List<TaskDoctorIssue>.toTaskDoctorMarkdown(
    status: String,
    totalTaskCount: Int,
    candidateTaskCount: Int,
    issueCount: Int,
    limit: Int,
    includeDisabled: Boolean,
): String {
    val includedIssues = this
    return buildString {
        appendLine("# Automation doctor")
        appendLine()
        appendLine("- Status: $status")
        appendLine("- Automations in inventory: $totalTaskCount")
        appendLine("- Candidate automations after filters: $candidateTaskCount")
        appendLine("- Issues included: ${includedIssues.size} of $issueCount")
        appendLine("- Limit: $limit")
        appendLine("- Disabled automations included: $includeDisabled")
        appendLine("- Task prompt bodies omitted: true")
        appendLine()
        appendLine("## Issues")
        if (includedIssues.isEmpty()) {
            appendLine("_No automation issues found._")
        } else {
            includedIssues.forEach { issue ->
                appendLine(issue.toTaskDoctorMarkdownLine())
            }
        }
    }
}

internal fun TaskDoctorIssue.toTaskDoctorMarkdownLine(): String =
    buildString {
        append("- ")
        append(severity)
        append(" `")
        append(taskName.toHandoffLine())
        append("` id=`")
        append(taskId.toHandoffLine())
        append("` code=")
        append(code)
        append(": ")
        append(summary.toHandoffLine())
        secondsOverdue?.let { overdue ->
            append(" secondsOverdue=")
            append(overdue)
        }
        detail?.let { detail ->
            append(" detail=")
            append(detail.toHandoffLine())
        }
        append(" Action: ")
        append(action.toHandoffLine())
    }

internal fun String.toTaskDoctorText(): String = toHandoffLine().take(TASK_DOCTOR_TEXT_MAX_CHARS)

internal fun Task.toTaskHandoffMarkdown(
    promptSnippet: String?,
    recentRuns: List<TaskRun>,
    runLimit: Int,
): String =
    buildString {
        appendLine("# Automation handoff: ${name.toHandoffLine()}")
        appendLine()
        appendLine("- Task id: `$id`")
        appendLine("- Enabled: $enabled")
        appendLine("- Schedule: ${schedule.toTaskSearchKind()}")
        appendLine("- Execution mode: ${executionMode.name}")
        appendLine("- Target session id: ${targetSessionId ?: "default"}")
        appendLine("- Precise requested: $precise")
        appendLine("- Next run: ${nextRunAt ?: "none"}")
        appendLine("- Last run: ${lastRunAt ?: "none"}")
        appendLine("- Failures/retries: $failureCount / $maxRetries")
        appendLine("- Recent runs included: ${recentRuns.size} of up to $runLimit")
        appendLine()
        appendLine("## Prompt")
        appendLine(promptSnippet?.toHandoffLine() ?: "_Prompt omitted._")
        appendLine()
        appendLine("## Recent runs")
        if (recentRuns.isEmpty()) {
            appendLine("_No recent runs included._")
        } else {
            recentRuns.forEach { run ->
                appendLine(run.toTaskRunMarkdownLine())
            }
        }
    }

internal fun TaskRepository.TaskStats.toTaskAgendaMarkdown(
    dueTasks: List<Task>,
    upcomingTasks: List<Task>,
    limit: Int,
    includePromptSnippets: Boolean,
    minimumBackgroundIntervalMinutes: Long,
): String =
    buildString {
        appendLine("# Automation agenda")
        appendLine()
        appendLine("- Automations: $totalTaskCount")
        appendLine("- Enabled: $enabledTaskCount")
        appendLine("- Disabled: $disabledTaskCount")
        appendLine("- Due now: $dueTaskCount")
        appendLine("- Next enabled run: ${nextEnabledRunAt ?: "none"}")
        appendLine("- Minimum background interval minutes: $minimumBackgroundIntervalMinutes")
        appendLine("- Due tasks included: ${dueTasks.size} of up to $limit")
        appendLine("- Upcoming tasks included: ${upcomingTasks.size} of up to $limit")
        appendLine("- Prompt snippets included: $includePromptSnippets")
        appendLine("- Prompt bodies included: false")
        appendLine()
        appendLine("## Due automations")
        if (dueTasks.isEmpty()) {
            appendLine("_No due enabled automations included._")
        } else {
            dueTasks.forEach { task ->
                appendLine(task.toTaskAgendaMarkdownLine(includePromptSnippets = includePromptSnippets))
            }
        }
        appendLine()
        appendLine("## Upcoming automations")
        if (upcomingTasks.isEmpty()) {
            appendLine("_No future enabled automations included._")
        } else {
            upcomingTasks.forEach { task ->
                appendLine(task.toTaskAgendaMarkdownLine(includePromptSnippets = includePromptSnippets))
            }
        }
    }

internal fun Task.toTaskAgendaMarkdownLine(includePromptSnippets: Boolean): String =
    buildString {
        append("- `")
        append(id.toHandoffLine())
        append("` ")
        append(name.toHandoffLine())
        append(" next=")
        append(nextRunAt ?: "none")
        append(" schedule=")
        append(schedule.toTaskSearchKind())
        append(" mode=")
        append(executionMode.name)
        targetSessionId?.let { sessionId ->
            append(" target=")
            append(sessionId.toHandoffLine())
        }
        append(" prompt=")
        if (includePromptSnippets) {
            append(prompt.toMessageSearchSnippet().toHandoffLine())
        } else {
            append("_omitted_")
        }
    }

internal fun Task.toTaskAgendaPayload(
    now: Instant,
    targetSession: Session?,
    diagnostics: SchedulerDiagnostics,
    includePromptSnippet: Boolean,
): JsonObject {
    val promptSnippet = prompt.toMessageSearchSnippet()
    val nextRun = nextRunAt
    val due = nextRun?.isAfter(now) == false
    val decision = schedulingDecision(diagnostics)
    val preciseWarnings = userVisiblePreciseWarnings(diagnostics)
    return buildJsonObject {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("scheduleKind", schedule.toTaskSearchKind())
        put("schedule", schedule.toPayload())
        put("executionMode", executionMode.name)
        put("targetSessionId", targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("targetSessionMissing", targetSessionId != null && targetSession == null)
        put("targetSessionArchived", targetSession?.archived?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "targetSession",
            targetSession?.let { session ->
                buildJsonObject {
                    put("id", session.id)
                    put("title", session.title)
                    put("isMain", session.isMain)
                    put("archived", session.archived)
                }
            } ?: JsonNull,
        )
        put("preciseRequested", precise)
        put("precisionMode", precisionMode.name)
        put("effectiveSchedulingPath", decision.path.name)
        put("degradedReason", decision.degradedReason?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "precisionWarnings",
            buildJsonArray {
                preciseWarnings.forEach { warning ->
                    add(JsonPrimitive(warning))
                }
            },
        )
        put("nextRunAtIso", nextRun?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("lastRunAtIso", lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("due", due)
        put(
            "secondsOverdue",
            nextRun
                ?.takeIf { due }
                ?.let { Duration.between(it, now).seconds.coerceAtLeast(0) }
                ?.let(::JsonPrimitive)
                ?: JsonNull,
        )
        put(
            "secondsUntilRun",
            nextRun
                ?.let { Duration.between(now, it).seconds }
                ?.let(::JsonPrimitive)
                ?: JsonNull,
        )
        put("failureCount", failureCount)
        put("maxRetries", maxRetries)
        put("createdAtIso", createdAt.toString())
        put("updatedAtIso", updatedAt.toString())
        put("promptIncluded", includePromptSnippet)
        put("promptSnippet", if (includePromptSnippet) JsonPrimitive(promptSnippet) else JsonNull)
        put("promptLength", prompt.length)
        put("promptTruncated", if (includePromptSnippet) promptSnippet.length < prompt.length else false)
        put("promptBodyIncluded", false)
    }
}

internal fun TaskRun.toTaskRunMarkdownLine(): String =
    buildString {
        append("- ")
        append(status.name)
        append(" scheduled ")
        append(scheduledAt)
        resultSummary?.let { summary ->
            append(" result: ")
            append(summary.toMessageSearchSnippet().toHandoffLine())
        }
        errorCode?.let { code ->
            append(" error: ")
            append(code.toHandoffLine())
        }
    }

internal fun TaskRun.toTaskRunHistoryPayload() =
    buildJsonObject {
        put("id", id)
        put("status", status.name)
        put("scheduledAtIso", scheduledAt.toString())
        put("startedAtIso", startedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("finishedAtIso", finishedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("resultSummary", resultSummary?.let(::JsonPrimitive) ?: JsonNull)
        put("errorCode", errorCode?.let(::JsonPrimitive) ?: JsonNull)
        put("errorMessage", errorMessage?.let(::JsonPrimitive) ?: JsonNull)
        put("outputMessageId", outputMessageId?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun TaskRun.toTaskRunWithTaskPayload(task: Task?): JsonObject =
    buildJsonObject {
        put("run", toTaskRunHistoryPayload())
        put("taskId", taskId)
        put("taskAvailable", task != null)
        put("taskName", task?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("taskEnabled", task?.enabled?.let(::JsonPrimitive) ?: JsonNull)
        put("taskNextRunAtIso", task?.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("taskFailureCount", task?.failureCount?.let(::JsonPrimitive) ?: JsonNull)
        put("taskMaxRetries", task?.maxRetries?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun Task.toTaskSearchPayload(): JsonObject {
    val promptSnippet = prompt.toMessageSearchSnippet()
    return buildJsonObject {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("scheduleKind", schedule.toTaskSearchKind())
        put("executionMode", executionMode.name)
        put("targetSessionId", targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("nextRunAtIso", nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("lastRunAtIso", lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("promptSnippet", promptSnippet)
        put("promptLength", prompt.length)
        put("promptTruncated", promptSnippet.length < prompt.length)
    }
}

internal fun Task.toUpcomingTaskPayload(now: Instant): JsonObject {
    val promptSnippet = prompt.toMessageSearchSnippet()
    val nextRun = nextRunAt
    return buildJsonObject {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("scheduleKind", schedule.toTaskSearchKind())
        put("executionMode", executionMode.name)
        put("targetSessionId", targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("nextRunAtIso", nextRun?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("lastRunAtIso", lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("due", nextRun?.isAfter(now) == false)
        put("secondsUntilRun", nextRun?.let { Duration.between(now, it).seconds }?.let(::JsonPrimitive) ?: JsonNull)
        put("promptSnippet", promptSnippet)
        put("promptLength", prompt.length)
        put("promptTruncated", promptSnippet.length < prompt.length)
    }
}

internal fun TaskSchedule.computeScheduledOccurrences(
    after: Instant,
    limit: Int,
): List<Instant> {
    val occurrences = mutableListOf<Instant>()
    var cursor = after
    for (index in 0 until limit.coerceAtLeast(0)) {
        val nextRun = NextRunCalculator.computeNextRun(this, cursor) ?: break
        if (!nextRun.isAfter(cursor)) {
            break
        }
        occurrences += nextRun
        cursor = nextRun
    }
    return occurrences
}

internal fun TaskSchedule.toScheduledOccurrencePayload(
    occurrence: Instant,
    index: Int,
    after: Instant,
    now: Instant,
): JsonObject =
    buildJsonObject {
        put("index", index)
        put("runAtIso", occurrence.toString())
        put("scheduleKind", toTaskSearchKind())
        put("dueAtNow", !occurrence.isAfter(now))
        put("secondsAfterLowerBound", Duration.between(after, occurrence).seconds)
        put("secondsFromNow", Duration.between(now, occurrence).seconds)
    }

internal data class TaskTimelineOccurrence(
    val task: Task,
    val runAt: Instant,
    val taskOccurrenceIndex: Int,
)

internal fun TaskTimelineOccurrence.toTaskTimelinePayload(
    index: Int,
    after: Instant,
    now: Instant,
    targetSession: Session?,
    includePromptSnippet: Boolean,
): JsonObject {
    val promptSnippet = task.prompt.toMessageSearchSnippet()
    return buildJsonObject {
        put("index", index)
        put("taskOccurrenceIndex", taskOccurrenceIndex)
        put("taskId", task.id)
        put("taskName", task.name)
        put("taskEnabled", task.enabled)
        put("scheduleKind", task.schedule.toTaskSearchKind())
        put("executionMode", task.executionMode.name)
        put("targetSessionId", task.targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("targetSessionMissing", task.targetSessionId != null && targetSession == null)
        put("targetSessionArchived", targetSession?.archived?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "targetSession",
            targetSession?.let { session ->
                buildJsonObject {
                    put("id", session.id)
                    put("title", session.title)
                    put("isMain", session.isMain)
                    put("archived", session.archived)
                }
            } ?: JsonNull,
        )
        put("runAtIso", runAt.toString())
        put("nextRunAtIso", task.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("lastRunAtIso", task.lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("dueAtNow", !runAt.isAfter(now))
        put("secondsAfterLowerBound", Duration.between(after, runAt).seconds)
        put("secondsFromNow", Duration.between(now, runAt).seconds)
        put("promptIncluded", includePromptSnippet)
        put("promptSnippet", if (includePromptSnippet) JsonPrimitive(promptSnippet) else JsonNull)
        put("promptLength", task.prompt.length)
        put("promptTruncated", if (includePromptSnippet) promptSnippet.length < task.prompt.length else false)
        put("promptBodyIncluded", false)
    }
}

internal fun List<TaskTimelineOccurrence>.toTaskTimelineMarkdown(
    now: Instant,
    after: Instant,
    before: Instant?,
    limit: Int,
    perTaskLimit: Int,
    candidateTaskCount: Int,
    generatedOccurrenceCount: Int,
    includeDisabled: Boolean,
    includePromptSnippets: Boolean,
): String =
    buildString {
        appendLine("# Automation timeline")
        appendLine()
        appendLine("- Now: $now")
        appendLine("- After: $after")
        appendLine("- Before: ${before ?: "none"}")
        appendLine("- Candidate automations: $candidateTaskCount")
        appendLine("- Generated occurrences: $generatedOccurrenceCount")
        appendLine("- Occurrences included: ${this@toTaskTimelineMarkdown.size} of up to $limit")
        appendLine("- Per-task occurrence cap: $perTaskLimit")
        appendLine("- Disabled automations included: $includeDisabled")
        appendLine("- Prompt snippets included: $includePromptSnippets")
        appendLine("- Prompt bodies included: false")
        appendLine()
        appendLine("## Occurrences")
        if (this@toTaskTimelineMarkdown.isEmpty()) {
            appendLine("_No scheduled occurrences included._")
        } else {
            this@toTaskTimelineMarkdown.forEachIndexed { index, occurrence ->
                appendLine(occurrence.toTaskTimelineMarkdownLine(index = index, includePromptSnippets = includePromptSnippets))
            }
        }
    }

internal fun TaskTimelineOccurrence.toTaskTimelineMarkdownLine(
    index: Int,
    includePromptSnippets: Boolean,
): String =
    buildString {
        append("- ")
        append(index)
        append(" at=")
        append(runAt)
        append(" task=`")
        append(task.id.toHandoffLine())
        append("` ")
        append(task.name.toHandoffLine())
        append(" enabled=")
        append(task.enabled)
        append(" schedule=")
        append(task.schedule.toTaskSearchKind())
        append(" mode=")
        append(task.executionMode.name)
        task.targetSessionId?.let { sessionId ->
            append(" target=")
            append(sessionId.toHandoffLine())
        }
        append(" prompt=")
        if (includePromptSnippets) {
            append(task.prompt.toMessageSearchSnippet().toHandoffLine())
        } else {
            append("_omitted_")
        }
    }

internal fun Task.toDueTaskPayload(now: Instant): JsonObject {
    val promptSnippet = prompt.toMessageSearchSnippet()
    val nextRun = nextRunAt
    return buildJsonObject {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("scheduleKind", schedule.toTaskSearchKind())
        put("executionMode", executionMode.name)
        put("targetSessionId", targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("nextRunAtIso", nextRun?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("lastRunAtIso", lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("due", nextRun?.isAfter(now) == false)
        put(
            "secondsOverdue",
            nextRun
                ?.let { Duration.between(it, now).seconds.coerceAtLeast(0) }
                ?.let(::JsonPrimitive)
                ?: JsonNull,
        )
        put("promptSnippet", promptSnippet)
        put("promptLength", prompt.length)
        put("promptTruncated", promptSnippet.length < prompt.length)
    }
}

internal fun TaskSchedule.toTaskSearchKind(): String =
    when (this) {
        is TaskSchedule.Once -> "once"
        is TaskSchedule.Interval -> "interval"
        is TaskSchedule.Cron -> "cron"
    }

internal fun JsonObject.parseTaskSnoozeUntil(now: Instant): Instant {
    val untilText = optionalText("untilIso")
    val delayText = optionalText("delayMinutes")
    if (untilText != null && delayText != null) {
        throw IllegalArgumentException("tasks.snooze accepts either untilIso or delayMinutes, not both.")
    }
    if (untilText != null) {
        val until =
            try {
                Instant.parse(untilText)
            } catch (error: DateTimeParseException) {
                throw IllegalArgumentException("tasks.snooze requires untilIso to be an ISO-8601 instant.", error)
            }
        require(until.isAfter(now)) { "tasks.snooze requires untilIso to be after now." }
        requireTaskSnoozeDelay(Duration.between(now, until))
        return until
    }
    val delayMinutes =
        delayText
            ?.toLongOrNull()
            ?: if (delayText == null) {
                TASK_SNOOZE_DEFAULT_DELAY_MINUTES
            } else {
                throw IllegalArgumentException("tasks.snooze received a non-numeric delayMinutes.")
            }
    require(delayMinutes > 0L) { "tasks.snooze requires delayMinutes > 0." }
    require(delayMinutes <= TASK_SNOOZE_MAX_DELAY_MINUTES) {
        "tasks.snooze requires delayMinutes <= $TASK_SNOOZE_MAX_DELAY_MINUTES."
    }
    return now.plus(Duration.ofMinutes(delayMinutes))
}

internal fun requireTaskSnoozeDelay(delay: Duration) {
    require(!delay.isZero && !delay.isNegative) { "tasks.snooze requires a future snooze time." }
    require(delay <= Duration.ofMinutes(TASK_SNOOZE_MAX_DELAY_MINUTES)) {
        "tasks.snooze requires snooze delay <= $TASK_SNOOZE_MAX_DELAY_MINUTES minutes."
    }
}

internal fun taskCreateDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.create",
        aliases = listOf("task.create"),
        description = "Create a scheduled automation using explicit schedule fields.",
        arguments = taskMutationArguments(requiredTaskId = false),
    )

internal fun taskCreateExampleDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.create.example",
        aliases =
            listOf(
                "task.create.example",
                "tasks.schedule.example",
                "task.schedule.example",
                "tasks.automation.example",
                "automation.create.example",
                "automations.create.example",
            ),
        description = "Return safe example arguments for creating scheduled automations without creating them.",
        arguments =
            listOf(
                ToolArgumentSpec(
                    name = "scheduleKind",
                    description = "Optional once | interval | cron filter. Omit or use all for all examples.",
                ),
                ToolArgumentSpec(
                    name = "includeMarkdown",
                    description = "Set false to omit exampleMarkdown. Defaults to true.",
                ),
            ),
    )

internal fun taskUpdateDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.update",
        aliases = listOf("task.update"),
        description = "Patch an existing task without replacing unspecified fields.",
        arguments = taskMutationArguments(requiredTaskId = true),
    )

internal fun taskUpdateExampleDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.update.example",
        aliases =
            listOf(
                "task.update.example",
                "tasks.patch.example",
                "task.patch.example",
                "automation.update.example",
                "automations.update.example",
            ),
        description = "Return safe example arguments for patching scheduled automations without updating them.",
        arguments =
            listOf(
                ToolArgumentSpec(
                    name = "patchKind",
                    description = "Optional metadata | once | interval | cron filter. Omit or use all for all examples.",
                ),
                ToolArgumentSpec(
                    name = "scheduleKind",
                    description = "Alias for selecting once | interval | cron schedule patch examples.",
                ),
                ToolArgumentSpec(
                    name = "includeMarkdown",
                    description = "Set false to omit exampleMarkdown. Defaults to true.",
                ),
            ),
    )

internal fun taskDuplicateDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.duplicate",
        aliases = listOf("task.duplicate", "tasks.copy", "task.copy"),
        description = "Duplicate an existing scheduled automation, disabled by default.",
        arguments =
            listOf(
                ToolArgumentSpec(
                    name = "taskId",
                    required = true,
                    description = "Task identifier to copy",
                ),
                ToolArgumentSpec(
                    name = "name",
                    description = "Name for the copy. Defaults to Copy of the source task name.",
                ),
                ToolArgumentSpec(
                    name = "enabled",
                    description = "true to enable and schedule the copy. Defaults to false.",
                ),
            ),
    )

internal fun taskDuplicateExampleDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.duplicate.example",
        aliases =
            listOf(
                "task.duplicate.example",
                "tasks.copy.example",
                "task.copy.example",
                "automation.duplicate.example",
                "automations.duplicate.example",
            ),
        description = "Return safe example arguments for duplicating scheduled automations without copying them.",
        arguments =
            listOf(
                ToolArgumentSpec(
                    name = "copyMode",
                    description = "Optional disabled | enabled filter. Omit or use all for both examples.",
                ),
                ToolArgumentSpec(
                    name = "includeMarkdown",
                    description = "Set false to omit exampleMarkdown. Defaults to true.",
                ),
            ),
    )

internal fun taskToggleDescriptor(
    name: String,
    description: String,
): ToolDescriptor =
    ToolDescriptor(
        name = name,
        aliases = listOf(name.replaceFirst("tasks.", "task.")),
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

internal fun taskMutationArguments(requiredTaskId: Boolean): List<ToolArgumentSpec> =
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

internal val TASK_CREATE_EXAMPLE_SCHEDULE_KINDS = listOf("once", "interval", "cron")

internal fun taskCreateExampleScheduleKinds(requestedKind: String?): List<String>? {
    val normalized = requestedKind?.lowercase()?.replace("-", "_")
    return when (normalized) {
        null, "all", "any", "*" -> TASK_CREATE_EXAMPLE_SCHEDULE_KINDS
        "once", "interval", "cron" -> listOf(normalized)
        else -> null
    }
}

internal fun taskCreateExamplePayload(
    scheduleKind: String,
    now: Instant,
): JsonObject =
    buildJsonObject {
        put("scheduleKind", scheduleKind)
        put("toolName", "tasks.create")
        put("alias", "task.create")
        put("exampleOnly", true)
        put("executesTaskCreation", false)
        put("createsAutomation", false)
        put("schedulesWork", false)
        put("mutatesTasks", false)
        put(
            "requiredFields",
            listOf("name", "prompt", "scheduleKind").toToolStringArrayPayload(),
        )
        put(
            "scheduleFields",
            taskCreateExampleScheduleFields(scheduleKind).toToolStringArrayPayload(),
        )
        put(
            "optionalFields",
            listOf(
                "executionMode",
                "targetSessionId",
                "targetSessionAlias",
                "precise",
                "maxRetries",
            ).toToolStringArrayPayload(),
        )
        put("exampleArguments", taskCreateExampleArguments(scheduleKind = scheduleKind, now = now))
        put("previewArguments", taskCreatePreviewArguments(scheduleKind = scheduleKind, now = now))
    }

internal fun taskCreateExampleScheduleFields(scheduleKind: String): List<String> =
    when (scheduleKind) {
        "once" -> listOf("atIso")
        "interval" -> listOf("anchorAtIso", "repeatEveryMinutes")
        "cron" -> listOf("cronExpression", "timezone")
        else -> emptyList()
    }

internal fun taskCreateExampleArguments(
    scheduleKind: String,
    now: Instant,
): JsonObject =
    buildJsonObject {
        put("name", taskCreateExampleName(scheduleKind))
        put("prompt", "Example prompt to run when this automation fires.")
        put("scheduleKind", scheduleKind)
        put("executionMode", "MAIN_SESSION")
        put("targetSessionAlias", "main")
        put("precise", false)
        put("maxRetries", 3)
        taskCreateExampleScheduleFields(scheduleKind = scheduleKind, now = now)
    }

internal fun taskCreatePreviewArguments(
    scheduleKind: String,
    now: Instant,
): JsonObject =
    buildJsonObject {
        put("scheduleKind", scheduleKind)
        taskCreateExampleScheduleFields(scheduleKind = scheduleKind, now = now)
    }

internal fun kotlinx.serialization.json.JsonObjectBuilder.taskCreateExampleScheduleFields(
    scheduleKind: String,
    now: Instant,
) {
    when (scheduleKind) {
        "once" -> put("atIso", now.plus(Duration.ofDays(1)).toString())
        "interval" -> {
            put("anchorAtIso", now.plus(Duration.ofMinutes(15)).toString())
            put("repeatEveryMinutes", 60)
        }
        "cron" -> {
            put("cronExpression", "0 9 * * *")
            put("timezone", "UTC")
        }
    }
}

internal fun taskCreateExampleName(scheduleKind: String): String =
    when (scheduleKind) {
        "once" -> "Example one-time automation"
        "interval" -> "Example interval automation"
        "cron" -> "Example cron automation"
        else -> "Example automation"
    }

internal fun taskCreateExampleMarkdown(
    requestedKind: String?,
    examples: JsonArray,
): String =
    buildString {
        appendLine("# Task create examples")
        appendLine()
        appendLine("- Requested schedule kind: ${requestedKind?.toHandoffLine() ?: "all"}")
        appendLine("- Example only: true")
        appendLine("- Creates automation: false")
        appendLine("- Schedules work: false")
        appendLine("- Suggested flow: `tasks.preview` then `tasks.create`.")
        appendLine()
        examples.forEach { example ->
            val exampleObject = example.jsonObject
            val scheduleKind = exampleObject.getValue("scheduleKind").jsonPrimitive.content
            appendLine("## $scheduleKind")
            appendLine()
            appendLine("```json")
            appendLine(exampleObject.getValue("exampleArguments").toString())
            appendLine("```")
            appendLine()
        }
    }

internal val TASK_DUPLICATE_EXAMPLE_COPY_MODES = listOf("disabled", "enabled")

internal fun taskDuplicateExampleCopyModes(requestedMode: String?): List<String>? {
    val normalized = requestedMode?.lowercase()?.replace("-", "_")
    return when (normalized) {
        null, "all", "any", "*" -> TASK_DUPLICATE_EXAMPLE_COPY_MODES
        "disabled", "safe", "draft" -> listOf("disabled")
        "enabled", "active", "scheduled" -> listOf("enabled")
        else -> null
    }
}

internal fun taskDuplicateExamplePayload(copyMode: String): JsonObject {
    val enabled = copyMode == "enabled"
    return buildJsonObject {
        put("copyMode", copyMode)
        put("toolName", "tasks.duplicate")
        put("alias", "task.duplicate")
        put("exampleOnly", true)
        put("executesTaskDuplicate", false)
        put("duplicatesAutomation", false)
        put("createsAutomation", false)
        put("schedulesWork", false)
        put("mutatesTasks", false)
        put("wouldCreateEnabledCopy", enabled)
        put("wouldScheduleCopy", enabled)
        put("requiredFields", listOf("taskId").toToolStringArrayPayload())
        put("optionalFields", listOf("name", "enabled").toToolStringArrayPayload())
        put(
            "sourceFieldsCopiedByRealTool",
            listOf(
                "prompt",
                "schedule",
                "executionMode",
                "targetSessionId",
                "precise",
                "maxRetries",
            ).toToolStringArrayPayload(),
        )
        put(
            "sourceFieldsNotIncludedInExample",
            listOf("promptBody", "runHistory", "providerMetadata").toToolStringArrayPayload(),
        )
        put(
            "exampleArguments",
            buildJsonObject {
                put("taskId", "example-task-id")
                put(
                    "name",
                    if (enabled) {
                        "Enabled copy of example automation"
                    } else {
                        "Copy of example automation"
                    },
                )
                put("enabled", enabled)
            },
        )
    }
}

internal fun taskDuplicateExampleMarkdown(
    requestedMode: String?,
    examples: JsonArray,
): String =
    buildString {
        appendLine("# Task duplicate examples")
        appendLine()
        appendLine("- Requested copy mode: ${requestedMode?.toHandoffLine() ?: "all"}")
        appendLine("- Example only: true")
        appendLine("- Duplicates automation: false")
        appendLine("- Creates automation: false")
        appendLine("- Schedules work: false")
        appendLine("- Suggested flow: `tasks.get` then `tasks.duplicate`.")
        appendLine()
        examples.forEach { example ->
            val exampleObject = example.jsonObject
            val copyMode = exampleObject.getValue("copyMode").jsonPrimitive.content
            appendLine("## $copyMode")
            appendLine()
            appendLine("```json")
            appendLine(exampleObject.getValue("exampleArguments").toString())
            appendLine("```")
            appendLine()
        }
    }

internal val TASK_UPDATE_EXAMPLE_PATCH_KINDS = listOf("metadata", "once", "interval", "cron")

internal fun taskUpdateExamplePatchKinds(requestedKind: String?): List<String>? {
    val normalized = requestedKind?.lowercase()?.replace("-", "_")
    return when (normalized) {
        null, "all", "any", "*" -> TASK_UPDATE_EXAMPLE_PATCH_KINDS
        "metadata", "metadata_only", "details" -> listOf("metadata")
        "once", "reschedule_once" -> listOf("once")
        "interval", "reschedule_interval" -> listOf("interval")
        "cron", "reschedule_cron" -> listOf("cron")
        else -> null
    }
}

internal fun taskUpdateExamplePayload(
    patchKind: String,
    now: Instant,
): JsonObject =
    buildJsonObject {
        put("patchKind", patchKind)
        put("toolName", "tasks.update")
        put("alias", "task.update")
        put("exampleOnly", true)
        put("executesTaskUpdate", false)
        put("updatesAutomation", false)
        put("schedulesWork", false)
        put("mutatesTasks", false)
        put("requiredFields", listOf("taskId").toToolStringArrayPayload())
        put("patchFields", taskUpdateExamplePatchFields(patchKind).toToolStringArrayPayload())
        put(
            "optionalFields",
            listOf(
                "name",
                "prompt",
                "scheduleKind",
                "atIso",
                "anchorAtIso",
                "repeatEveryMinutes",
                "cronExpression",
                "timezone",
                "executionMode",
                "targetSessionId",
                "targetSessionAlias",
                "precise",
                "maxRetries",
            ).toToolStringArrayPayload(),
        )
        put("exampleArguments", taskUpdateExampleArguments(patchKind = patchKind, now = now))
        put(
            "previewArguments",
            if (patchKind == "metadata") {
                JsonNull
            } else {
                taskCreatePreviewArguments(scheduleKind = patchKind, now = now)
            },
        )
        put("previewSupported", patchKind != "metadata")
    }

internal fun taskUpdateExamplePatchFields(patchKind: String): List<String> =
    when (patchKind) {
        "metadata" -> listOf("name", "prompt", "executionMode", "targetSessionAlias", "precise", "maxRetries")
        "once" -> listOf("scheduleKind", "atIso")
        "interval" -> listOf("scheduleKind", "anchorAtIso", "repeatEveryMinutes")
        "cron" -> listOf("scheduleKind", "cronExpression", "timezone")
        else -> emptyList()
    }

internal fun taskUpdateExampleArguments(
    patchKind: String,
    now: Instant,
): JsonObject =
    buildJsonObject {
        put("taskId", "example-task-id")
        when (patchKind) {
            "metadata" -> {
                put("name", "Renamed example automation")
                put("prompt", "Updated example prompt to run on the existing schedule.")
                put("executionMode", "MAIN_SESSION")
                put("targetSessionAlias", "main")
                put("precise", false)
                put("maxRetries", 3)
            }
            "once", "interval", "cron" -> {
                put("scheduleKind", patchKind)
                taskCreateExampleScheduleFields(scheduleKind = patchKind, now = now)
            }
        }
    }

internal fun taskUpdateExampleMarkdown(
    requestedKind: String?,
    examples: JsonArray,
): String =
    buildString {
        appendLine("# Task update examples")
        appendLine()
        appendLine("- Requested patch kind: ${requestedKind?.toHandoffLine() ?: "all"}")
        appendLine("- Example only: true")
        appendLine("- Updates automation: false")
        appendLine("- Schedules work: false")
        appendLine("- Suggested flow: `tasks.get`, optionally `tasks.preview`, then `tasks.update`.")
        appendLine()
        examples.forEach { example ->
            val exampleObject = example.jsonObject
            val patchKind = exampleObject.getValue("patchKind").jsonPrimitive.content
            appendLine("## $patchKind")
            appendLine()
            appendLine("```json")
            appendLine(exampleObject.getValue("exampleArguments").toString())
            appendLine("```")
            appendLine()
        }
    }
