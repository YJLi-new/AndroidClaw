package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.CronField
import ai.androidclaw.runtime.scheduler.NextRunCalculator
import ai.androidclaw.runtime.scheduler.SchedulerCapabilities
import ai.androidclaw.runtime.scheduler.SchedulerDiagnostics
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.runtime.scheduler.precisionMode
import ai.androidclaw.runtime.scheduler.schedulingDecision
import ai.androidclaw.runtime.scheduler.userVisiblePreciseWarnings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

internal data class TaskToolSpec(
    val name: String,
    val prompt: String,
    val schedule: TaskSchedule,
    val executionMode: TaskExecutionMode,
    val targetSessionId: String?,
    val precise: Boolean,
    val maxRetries: Int,
)

internal sealed interface TaskToolParseResult<out T> {
    data class Success<T>(
        val value: T,
    ) : TaskToolParseResult<T>

    data class Failure(
        val result: ToolExecutionResult,
    ) : TaskToolParseResult<Nothing>
}

internal suspend fun buildTaskPayload(
    task: Task,
    latestRun: TaskRun?,
    sessionRepository: SessionRepository,
    diagnostics: SchedulerDiagnostics,
): JsonObject {
    val resolvedSession =
        if (task.targetSessionId != null) {
            sessionRepository.getSession(task.targetSessionId) ?: sessionRepository.getOrCreateMainSession()
        } else {
            sessionRepository.getOrCreateMainSession()
        }
    val decision = task.schedulingDecision(diagnostics)
    val preciseWarnings = task.userVisiblePreciseWarnings(diagnostics)
    return buildJsonObject {
        put("id", task.id)
        put("name", task.name)
        put("prompt", task.prompt)
        put("scheduleKind", task.schedule.kindName())
        put("schedule", task.schedule.toPayload())
        put("executionMode", task.executionMode.storageName())
        put("targetSessionId", task.targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "resolvedTargetSession",
            buildJsonObject {
                put("id", resolvedSession.id)
                put("title", resolvedSession.title)
                put("isMain", resolvedSession.isMain)
            },
        )
        put("enabled", task.enabled)
        put("preciseRequested", task.precise)
        put("precisionMode", task.precisionMode.name)
        put("effectiveSchedulingPath", decision.path.name)
        put("degradedReason", decision.degradedReason?.let(::JsonPrimitive) ?: JsonNull)
        put("precisionWarnings", preciseWarnings.toJsonArray())
        put("nextRunAtIso", task.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("lastRunAtIso", task.lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("failureCount", task.failureCount)
        put("maxRetries", task.maxRetries)
        put("createdAtIso", task.createdAt.toString())
        put("updatedAtIso", task.updatedAt.toString())
        put("lastRun", latestRun?.toPayload() ?: JsonNull)
    }
}

internal fun taskNotFoundResult(
    toolName: String,
    taskId: String,
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Task $taskId was not found.",
        errorCode = "TASK_NOT_FOUND",
        payload =
            buildJsonObject {
                put("errorCode", "TASK_NOT_FOUND")
                put("toolName", toolName)
                put("taskId", taskId)
            },
    )

internal fun invalidTaskArguments(
    toolName: String,
    summary: String,
    field: String? = null,
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = summary,
        errorCode = "INVALID_ARGUMENTS",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_ARGUMENTS")
                put("toolName", toolName)
                put("field", field?.let(::JsonPrimitive) ?: JsonNull)
            },
    )

internal suspend fun parseTaskCreateSpec(
    arguments: JsonObject,
    context: ToolExecutionContext,
    sessionRepository: SessionRepository,
    capabilities: SchedulerCapabilities,
    now: Instant,
): TaskToolParseResult<TaskToolSpec> {
    val name =
        arguments.requiredString(
            field = "name",
            toolName = "tasks.create",
        ) ?: return TaskToolParseResult.Failure(
            invalidTaskArguments(
                toolName = "tasks.create",
                summary = "tasks.create requires a non-empty name.",
                field = "name",
            ),
        )
    val prompt =
        arguments.requiredString(
            field = "prompt",
            toolName = "tasks.create",
        ) ?: return TaskToolParseResult.Failure(
            invalidTaskArguments(
                toolName = "tasks.create",
                summary = "tasks.create requires a non-empty prompt.",
                field = "prompt",
            ),
        )
    val schedule =
        parseSchedule(
            arguments = arguments,
            existingSchedule = null,
            capabilities = capabilities,
            now = now,
            toolName = "tasks.create",
        ) ?: return TaskToolParseResult.Failure(
            invalidTaskArguments(
                toolName = "tasks.create",
                summary = "tasks.create requires a valid schedule payload.",
                field = "scheduleKind",
            ),
        )
    val executionMode =
        parseExecutionMode(arguments["executionMode"], toolName = "tasks.create")
            ?: TaskExecutionMode.MainSession
    val targetSessionIdResult =
        resolveTargetSessionId(
            toolName = "tasks.create",
            arguments = arguments,
            context = context,
            sessionRepository = sessionRepository,
            existingTargetSessionId = null,
            defaultToMainSession = true,
        )
    val targetSessionId =
        when (targetSessionIdResult) {
            is TaskToolParseResult.Success -> targetSessionIdResult.value
            is TaskToolParseResult.Failure -> return targetSessionIdResult
        }
    val precise =
        parseBooleanArgument(arguments["precise"], toolName = "tasks.create", field = "precise")
            ?: false
    val maxRetries =
        parseRetryCount(arguments["maxRetries"], toolName = "tasks.create")
            ?: 3
    return TaskToolParseResult.Success(
        TaskToolSpec(
            name = name,
            prompt = prompt,
            schedule = schedule,
            executionMode = executionMode,
            targetSessionId = targetSessionId,
            precise = precise,
            maxRetries = maxRetries,
        ),
    )
}

internal suspend fun parseTaskUpdate(
    existingTask: Task,
    arguments: JsonObject,
    context: ToolExecutionContext,
    sessionRepository: SessionRepository,
    capabilities: SchedulerCapabilities,
    now: Instant,
): TaskToolParseResult<Task> {
    val name = arguments.optionalString(field = "name", toolName = "tasks.update") ?: existingTask.name
    val prompt = arguments.optionalString(field = "prompt", toolName = "tasks.update") ?: existingTask.prompt
    val schedule =
        parseSchedule(
            arguments = arguments,
            existingSchedule = existingTask.schedule,
            capabilities = capabilities,
            now = now,
            toolName = "tasks.update",
        ) ?: return TaskToolParseResult.Failure(
            invalidTaskArguments(
                toolName = "tasks.update",
                summary = "tasks.update requires a valid schedule patch.",
                field = "scheduleKind",
            ),
        )
    val executionMode =
        parseExecutionMode(arguments["executionMode"], toolName = "tasks.update")
            ?: existingTask.executionMode
    val targetSessionIdResult =
        resolveTargetSessionId(
            toolName = "tasks.update",
            arguments = arguments,
            context = context,
            sessionRepository = sessionRepository,
            existingTargetSessionId = existingTask.targetSessionId,
            defaultToMainSession = false,
        )
    val targetSessionId =
        when (targetSessionIdResult) {
            is TaskToolParseResult.Success -> targetSessionIdResult.value
            is TaskToolParseResult.Failure -> return targetSessionIdResult
        }
    val precise =
        parseBooleanArgument(arguments["precise"], toolName = "tasks.update", field = "precise")
            ?: existingTask.precise
    val maxRetries =
        parseRetryCount(arguments["maxRetries"], toolName = "tasks.update")
            ?: existingTask.maxRetries

    return TaskToolParseResult.Success(
        existingTask.copy(
            name = name,
            prompt = prompt,
            schedule = schedule,
            executionMode = executionMode,
            targetSessionId = targetSessionId,
            precise = precise,
            nextRunAt = taskNextRun(schedule = schedule, now = now),
            updatedAt = now,
            maxRetries = maxRetries,
        ),
    )
}

private suspend fun resolveTargetSessionId(
    toolName: String,
    arguments: JsonObject,
    context: ToolExecutionContext,
    sessionRepository: SessionRepository,
    existingTargetSessionId: String?,
    defaultToMainSession: Boolean,
): TaskToolParseResult<String?> {
    val explicitId = arguments.optionalString(field = "targetSessionId", toolName = toolName)
    val explicitAlias = arguments.optionalString(field = "targetSessionAlias", toolName = toolName)

    if (explicitId != null && explicitAlias != null) {
        return TaskToolParseResult.Failure(
            invalidTaskArguments(
                toolName = toolName,
                summary = "$toolName accepts either targetSessionId or targetSessionAlias, not both.",
                field = "targetSessionAlias",
            ),
        )
    }

    if (explicitId != null) {
        val targetSession = sessionRepository.getSession(explicitId)
        if (targetSession == null) {
            return TaskToolParseResult.Failure(
                invalidTaskArguments(
                    toolName = toolName,
                    summary = "Target session $explicitId was not found.",
                    field = "targetSessionId",
                ),
            )
        }
        return TaskToolParseResult.Success(targetSession.id)
    }

    if (explicitAlias != null) {
        return when (explicitAlias.lowercase()) {
            "main" -> TaskToolParseResult.Success(sessionRepository.getOrCreateMainSession().id)
            "current" -> {
                context.sessionId?.let { TaskToolParseResult.Success<String?>(it) } ?: TaskToolParseResult.Failure(
                    invalidTaskArguments(
                        toolName = toolName,
                        summary = "targetSessionAlias=current requires a session-bound tool execution context.",
                        field = "targetSessionAlias",
                    ),
                )
            }

            else ->
                TaskToolParseResult.Failure(
                    invalidTaskArguments(
                        toolName = toolName,
                        summary = "Unsupported targetSessionAlias: $explicitAlias.",
                        field = "targetSessionAlias",
                    ),
                )
        }
    }

    if (existingTargetSessionId != null) {
        return TaskToolParseResult.Success(existingTargetSessionId)
    }

    return if (defaultToMainSession) {
        TaskToolParseResult.Success(sessionRepository.getOrCreateMainSession().id)
    } else {
        TaskToolParseResult.Success(null)
    }
}

private fun parseExecutionMode(
    element: kotlinx.serialization.json.JsonElement?,
    toolName: String,
): TaskExecutionMode? {
    val rawValue =
        element
            ?.let {
                it as? JsonPrimitive
            }?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty) ?: return null
    return when (rawValue.uppercase()) {
        "MAIN_SESSION" -> TaskExecutionMode.MainSession
        "ISOLATED_SESSION" -> TaskExecutionMode.IsolatedSession
        else -> throw IllegalArgumentException("$toolName received unsupported executionMode: $rawValue.")
    }
}

private fun parseSchedule(
    arguments: JsonObject,
    existingSchedule: TaskSchedule?,
    capabilities: SchedulerCapabilities,
    now: Instant,
    toolName: String,
): TaskSchedule? {
    val scheduleKind =
        arguments
            .optionalString(field = "scheduleKind", toolName = toolName)
            ?.lowercase()
            ?: existingSchedule?.kindName()
            ?: return null
    return when (scheduleKind) {
        "once" -> {
            val atIso =
                arguments.optionalString(field = "atIso", toolName = toolName)
                    ?: (existingSchedule as? TaskSchedule.Once)?.at?.toString()
                    ?: return null
            val at =
                parseInstantArgument(
                    value = atIso,
                    toolName = toolName,
                    field = "atIso",
                ) ?: return null
            require(at.isAfter(now)) {
                "$toolName requires once schedules to be in the future."
            }
            TaskSchedule.Once(at)
        }

        "interval" -> {
            val anchorAtIso =
                arguments.optionalString(field = "anchorAtIso", toolName = toolName)
                    ?: (existingSchedule as? TaskSchedule.Interval)?.anchorAt?.toString()
                    ?: return null
            val repeatEveryMinutes =
                parsePositiveMinutes(
                    element = arguments["repeatEveryMinutes"],
                    toolName = toolName,
                    field = "repeatEveryMinutes",
                )
                    ?: (existingSchedule as? TaskSchedule.Interval)?.repeatEvery?.toMinutes()
                    ?: return null
            require(repeatEveryMinutes >= capabilities.minimumBackgroundInterval.toMinutes()) {
                "$toolName requires repeatEveryMinutes >= ${capabilities.minimumBackgroundInterval.toMinutes()}."
            }
            TaskSchedule.Interval(
                anchorAt =
                    parseInstantArgument(
                        value = anchorAtIso,
                        toolName = toolName,
                        field = "anchorAtIso",
                    ) ?: return null,
                repeatEvery = Duration.ofMinutes(repeatEveryMinutes),
            )
        }

        "cron" -> {
            val cronExpression =
                arguments.optionalString(field = "cronExpression", toolName = toolName)
                    ?: (existingSchedule as? TaskSchedule.Cron)?.expression?.toSpec()
                    ?: return null
            val timezone =
                arguments.optionalString(field = "timezone", toolName = toolName)
                    ?: (existingSchedule as? TaskSchedule.Cron)?.zoneId?.id
                    ?: return null
            val parsedZoneId =
                try {
                    ZoneId.of(timezone)
                } catch (_: Exception) {
                    throw IllegalArgumentException("$toolName received unsupported timezone: $timezone.")
                }
            val expression =
                try {
                    CronExpression.parse(cronExpression)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("$toolName received invalid cronExpression: ${error.message}")
                }
            val schedule =
                TaskSchedule.Cron(
                    expression = expression,
                    zoneId = parsedZoneId,
                )
            require(NextRunCalculator.computeNextRun(schedule, now) != null) {
                "$toolName produced no next run for cronExpression=$cronExpression."
            }
            schedule
        }

        else -> throw IllegalArgumentException("$toolName received unsupported scheduleKind: $scheduleKind.")
    }
}

private fun parseInstantArgument(
    value: String,
    toolName: String,
    field: String,
): Instant? =
    try {
        Instant.parse(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("$toolName received invalid $field: $value.")
    }

private fun parsePositiveMinutes(
    element: kotlinx.serialization.json.JsonElement?,
    toolName: String,
    field: String,
): Long? {
    val primitive = element as? JsonPrimitive ?: return null
    val value =
        primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
            ?: throw IllegalArgumentException("$toolName received a non-numeric $field.")
    require(value > 0) { "$toolName requires $field > 0." }
    return value
}

private fun parseRetryCount(
    element: kotlinx.serialization.json.JsonElement?,
    toolName: String,
): Int? {
    val primitive = element as? JsonPrimitive ?: return null
    val value =
        primitive.longOrNull?.toInt() ?: primitive.contentOrNull?.toIntOrNull()
            ?: throw IllegalArgumentException("$toolName received a non-numeric maxRetries.")
    require(value >= 0) { "$toolName requires maxRetries >= 0." }
    return value
}

private fun parseBooleanArgument(
    element: kotlinx.serialization.json.JsonElement?,
    toolName: String,
    field: String,
): Boolean? {
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.booleanOrNull ?: when (primitive.contentOrNull?.lowercase()) {
        "true" -> true
        "false" -> false
        null -> null
        else -> throw IllegalArgumentException("$toolName received a non-boolean $field.")
    }
}

private fun JsonObject.requiredString(
    field: String,
    toolName: String,
): String? = optionalString(field = field, toolName = toolName)

private fun JsonObject.optionalString(
    field: String,
    toolName: String,
): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    val value = primitive.contentOrNull?.trim() ?: return null
    if (value.isEmpty()) {
        throw IllegalArgumentException("$toolName received an empty $field.")
    }
    return value
}

private fun taskNextRun(
    schedule: TaskSchedule,
    now: Instant,
): Instant? =
    when (schedule) {
        is TaskSchedule.Once -> schedule.at
        is TaskSchedule.Interval -> if (schedule.anchorAt.isAfter(now)) schedule.anchorAt else NextRunCalculator.computeNextRun(schedule, now)
        is TaskSchedule.Cron -> NextRunCalculator.computeNextRun(schedule, now)
    }

private fun TaskSchedule.kindName(): String =
    when (this) {
        is TaskSchedule.Once -> "once"
        is TaskSchedule.Interval -> "interval"
        is TaskSchedule.Cron -> "cron"
    }

private fun TaskSchedule.toPayload(): JsonObject =
    when (this) {
        is TaskSchedule.Once ->
            buildJsonObject {
                put("kind", "once")
                put("atIso", at.toString())
            }

        is TaskSchedule.Interval ->
            buildJsonObject {
                put("kind", "interval")
                put("anchorAtIso", anchorAt.toString())
                put("repeatEveryMinutes", repeatEvery.toMinutes())
            }

        is TaskSchedule.Cron ->
            buildJsonObject {
                put("kind", "cron")
                put("cronExpression", expression.toSpec())
                put("timezone", zoneId.id)
            }
    }

private fun TaskRun.toPayload(): JsonObject =
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

private fun TaskExecutionMode.storageName(): String =
    when (this) {
        TaskExecutionMode.MainSession -> "MAIN_SESSION"
        TaskExecutionMode.IsolatedSession -> "ISOLATED_SESSION"
    }

private fun List<String>.toJsonArray(): JsonArray =
    buildJsonArray {
        forEach { add(JsonPrimitive(it)) }
    }

private fun CronExpression.toSpec(): String =
    listOf(
        minute.toSpec(),
        hour.toSpec(),
        dayOfMonth.toSpec(),
        month.toSpec(),
        dayOfWeek.toSpec(),
    ).joinToString(" ")

private fun CronField.toSpec(): String = if (isWildcard) "*" else allowed.toList().sorted().joinToString(",")
