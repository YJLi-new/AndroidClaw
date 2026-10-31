package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.CronField
import ai.androidclaw.runtime.scheduler.MAX_SAFE_DURATION_MINUTES
import ai.androidclaw.runtime.scheduler.NextRunCalculator
import ai.androidclaw.runtime.scheduler.SchedulerCapabilities
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal data class TaskToolSpec(
    val name: String,
    val prompt: String,
    val schedule: TaskSchedule,
    val executionMode: TaskExecutionMode,
    val targetSessionId: String?,
    val precise: Boolean,
    val maxRetries: Int,
)

internal const val TASK_DEFAULT_MAX_RETRIES = 3

/** Parses `tasks_create` arguments. Invalid input throws [IllegalArgumentException]. */
internal suspend fun parseTaskCreateSpec(
    arguments: JsonObject,
    context: ToolExecutionContext,
    sessionRepository: SessionRepository,
    capabilities: SchedulerCapabilities,
    now: Instant,
    zoneId: ZoneId,
): TaskToolSpec {
    val toolName = "tasks_create"
    val name = arguments.requireText("name", toolName)
    val prompt = arguments.requireText("prompt", toolName)
    val schedule =
        parseSchedule(arguments, existingSchedule = null, capabilities, now, zoneId, toolName)
            ?: throw IllegalArgumentException("$toolName requires scheduleKind once (atIso), interval (repeatEveryMinutes) or cron (cronExpression).")
    return TaskToolSpec(
        name = name,
        prompt = prompt,
        schedule = schedule,
        executionMode = parseExecutionMode(arguments.optionalText("executionMode"), toolName) ?: TaskExecutionMode.MainSession,
        targetSessionId =
            resolveTargetSessionId(arguments, context, sessionRepository, existingTargetSessionId = null, toolName)
                ?: sessionRepository.getOrCreateMainSession().id,
        precise = arguments.optionalBooleanOrNull("precise") ?: false,
        maxRetries = parseRetryCount(arguments, toolName) ?: TASK_DEFAULT_MAX_RETRIES,
    )
}

/** Applies a `tasks_update` patch to [existingTask]. */
internal suspend fun parseTaskUpdate(
    existingTask: Task,
    arguments: JsonObject,
    context: ToolExecutionContext,
    sessionRepository: SessionRepository,
    capabilities: SchedulerCapabilities,
    now: Instant,
    zoneId: ZoneId,
): Task {
    val toolName = "tasks_update"
    val hasSchedulePatch = SCHEDULE_PATCH_FIELDS.any { field -> arguments.optionalText(field) != null }
    val schedule =
        if (hasSchedulePatch) {
            parseSchedule(arguments, existingTask.schedule, capabilities, now, zoneId, toolName)
                ?: throw IllegalArgumentException("$toolName received an incomplete schedule patch.")
        } else {
            existingTask.schedule
        }
    return existingTask.copy(
        name = arguments.optionalText("name") ?: existingTask.name,
        prompt = arguments.optionalText("prompt") ?: existingTask.prompt,
        schedule = schedule,
        executionMode = parseExecutionMode(arguments.optionalText("executionMode"), toolName) ?: existingTask.executionMode,
        targetSessionId =
            resolveTargetSessionId(arguments, context, sessionRepository, existingTask.targetSessionId, toolName),
        precise = arguments.optionalBooleanOrNull("precise") ?: existingTask.precise,
        enabled = arguments.optionalBooleanOrNull("enabled") ?: existingTask.enabled,
        nextRunAt = if (hasSchedulePatch) taskNextRun(schedule, now) else existingTask.nextRunAt,
        failureCount = if (hasSchedulePatch) 0 else existingTask.failureCount,
        maxRetries = parseRetryCount(arguments, toolName) ?: existingTask.maxRetries,
        updatedAt = now,
    )
}

private suspend fun resolveTargetSessionId(
    arguments: JsonObject,
    context: ToolExecutionContext,
    sessionRepository: SessionRepository,
    existingTargetSessionId: String?,
    toolName: String,
): String? {
    val requested =
        arguments.optionalText("targetSessionId")
            ?: arguments.optionalText("targetSessionAlias")
            ?: return existingTargetSessionId
    val session =
        resolveSession(requested, context, sessionRepository)
            ?: throw IllegalArgumentException("$toolName: target session $requested was not found.")
    require(!session.archived) { "$toolName: target session ${session.title} is archived." }
    return session.id
}

private fun parseExecutionMode(
    raw: String?,
    toolName: String,
): TaskExecutionMode? {
    val value = raw?.trim()?.lowercase()?.replace("-", "_") ?: return null
    return when (value) {
        "main", "main_session", "mainsession" -> TaskExecutionMode.MainSession
        "isolated", "isolated_session", "isolatedsession" -> TaskExecutionMode.IsolatedSession
        else -> throw IllegalArgumentException("$toolName: executionMode must be MAIN_SESSION or ISOLATED_SESSION.")
    }
}

private fun parseSchedule(
    arguments: JsonObject,
    existingSchedule: TaskSchedule?,
    capabilities: SchedulerCapabilities,
    now: Instant,
    zoneId: ZoneId,
    toolName: String,
): TaskSchedule? {
    val kind =
        arguments.optionalText("scheduleKind")?.lowercase()
            ?: existingSchedule?.kindName()
            ?: inferScheduleKind(arguments)
            ?: return null
    return when (kind) {
        "once" -> {
            val atText =
                arguments.optionalText("atIso")
                    ?: (existingSchedule as? TaskSchedule.Once)?.at?.toString()
                    ?: return null
            val at = parseInstantArgument(atText, zoneId, toolName, "atIso")
            require(at.isAfter(now)) { "$toolName: atIso must be in the future (now is ${now.atZone(zoneId).toLocalDateTime()} $zoneId)." }
            TaskSchedule.Once(at)
        }

        "interval" -> {
            val minutes =
                arguments.optionalLongOrNull("repeatEveryMinutes")
                    ?: (existingSchedule as? TaskSchedule.Interval)?.repeatEvery?.toMinutes()
                    ?: return null
            val minimum = capabilities.minimumBackgroundInterval.toMinutes()
            require(minutes >= minimum) { "$toolName: repeatEveryMinutes must be at least $minimum." }
            require(minutes <= MAX_SAFE_DURATION_MINUTES) { "$toolName: repeatEveryMinutes is too large." }
            val anchor =
                arguments.optionalText("anchorAtIso")?.let { parseInstantArgument(it, zoneId, toolName, "anchorAtIso") }
                    ?: (existingSchedule as? TaskSchedule.Interval)?.anchorAt
                    ?: now
            TaskSchedule.Interval(anchorAt = anchor, repeatEvery = Duration.ofMinutes(minutes))
        }

        "cron" -> {
            val expressionText =
                arguments.optionalText("cronExpression")
                    ?: (existingSchedule as? TaskSchedule.Cron)?.expression?.toSpec()
                    ?: return null
            val zone =
                arguments.optionalText("timezone")?.let { timezone ->
                    runCatching { ZoneId.of(timezone) }.getOrNull()
                        ?: throw IllegalArgumentException("$toolName: unknown timezone $timezone.")
                }
                    ?: (existingSchedule as? TaskSchedule.Cron)?.zoneId
                    ?: zoneId
            val expression =
                try {
                    CronExpression.parse(expressionText)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("$toolName: invalid cronExpression ($expressionText): ${error.message}")
                }
            val schedule = TaskSchedule.Cron(expression = expression, zoneId = zone)
            require(NextRunCalculator.computeNextRun(schedule, now) != null) {
                "$toolName: cronExpression $expressionText never fires."
            }
            schedule
        }

        else -> throw IllegalArgumentException("$toolName: scheduleKind must be once, interval or cron.")
    }
}

private fun inferScheduleKind(arguments: JsonObject): String? =
    when {
        arguments.optionalText("cronExpression") != null -> "cron"
        arguments.optionalText("repeatEveryMinutes") != null -> "interval"
        arguments.optionalText("atIso") != null -> "once"
        else -> null
    }

/** Accepts `2026-03-08T09:00:00Z`, offsets, zoned values, or local date-times in [zoneId]. */
internal fun parseInstantArgument(
    value: String,
    zoneId: ZoneId,
    toolName: String,
    field: String,
): Instant {
    val text = value.trim()
    runCatching { return Instant.parse(text) }
    runCatching { return OffsetDateTime.parse(text).toInstant() }
    runCatching { return ZonedDateTime.parse(text).toInstant() }
    runCatching { return LocalDateTime.parse(text).atZone(zoneId).toInstant() }
    throw IllegalArgumentException("$toolName: $field must be an ISO-8601 date-time such as 2026-03-08T09:30 (got $text).")
}

private fun parseRetryCount(
    arguments: JsonObject,
    toolName: String,
): Int? {
    val value = arguments.optionalLongOrNull("maxRetries") ?: return null
    require(value in 0..MAX_TASK_RETRIES) { "$toolName: maxRetries must be between 0 and $MAX_TASK_RETRIES." }
    return value.toInt()
}

internal fun taskNextRun(
    schedule: TaskSchedule,
    now: Instant,
): Instant? =
    when (schedule) {
        is TaskSchedule.Once -> schedule.at
        is TaskSchedule.Interval ->
            if (schedule.anchorAt.isAfter(now)) schedule.anchorAt else NextRunCalculator.computeNextRun(schedule, now)
        is TaskSchedule.Cron -> NextRunCalculator.computeNextRun(schedule, now)
    }

internal fun TaskSchedule.kindName(): String =
    when (this) {
        is TaskSchedule.Once -> "once"
        is TaskSchedule.Interval -> "interval"
        is TaskSchedule.Cron -> "cron"
    }

internal fun TaskSchedule.describe(zoneId: ZoneId): String =
    when (this) {
        is TaskSchedule.Once -> "once at ${at.atZone(zoneId).toLocalDateTime()}"
        is TaskSchedule.Interval -> "every ${repeatEvery.toMinutes()} min"
        is TaskSchedule.Cron -> "cron \"${expression.toSpec()}\" ($zoneId)"
    }

internal fun Task.describeLine(zoneId: ZoneId): String =
    buildString {
        append("- ").append(name.clip(80)).append(" id=").append(id)
        append(" · ").append(schedule.describe(this@describeLine.schedule.displayZone(zoneId)))
        append(" · ").append(if (enabled) "enabled" else "disabled")
        append(" · ").append(if (executionMode == TaskExecutionMode.MainSession) "main session" else "isolated")
        nextRunAt?.let { next -> append(" · next ").append(next.atZone(zoneId).toLocalDateTime()) }
        if (failureCount > 0) append(" · failures ").append(failureCount)
    }

private fun TaskSchedule.displayZone(fallback: ZoneId): ZoneId = (this as? TaskSchedule.Cron)?.zoneId ?: fallback

internal fun Task.toCompactPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("name", name)
        put("schedule", schedule.kindName())
        put("enabled", enabled)
        put("executionMode", if (executionMode == TaskExecutionMode.MainSession) "MAIN_SESSION" else "ISOLATED_SESSION")
        put("nextRunAt", nextRunAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun TaskRun.describeLine(zoneId: ZoneId): String =
    buildString {
        append("- ").append(status.name)
        append(" scheduled ").append(scheduledAt.atZone(zoneId).toLocalDateTime())
        finishedAt?.let { append(", finished ").append(it.atZone(zoneId).toLocalDateTime()) }
        resultSummary?.takeIf(String::isNotBlank)?.let { append(": ").append(it.singleLine().clip(200)) }
        errorMessage?.takeIf(String::isNotBlank)?.let { append(" error: ").append(it.singleLine().clip(200)) }
    }

internal fun CronExpression.toSpec(): String = listOf(minute, hour, dayOfMonth, month, dayOfWeek).joinToString(" ") { field -> field.toSpec() }

private fun CronField.toSpec(): String = if (isWildcard) "*" else allowed.toList().sorted().joinToString(",")

private const val MAX_TASK_RETRIES = 10L

private val SCHEDULE_PATCH_FIELDS =
    setOf("scheduleKind", "atIso", "anchorAtIso", "repeatEveryMinutes", "cronExpression", "timezone")
