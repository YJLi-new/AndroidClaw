package ai.androidclaw.feature.tasks

import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.MAX_SAFE_DURATION_MINUTES
import ai.androidclaw.runtime.scheduler.NextRunCalculator
import ai.androidclaw.runtime.scheduler.TaskSchedule
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

internal enum class TaskScheduleKindUi {
    Once,
    Interval,
    Cron,
}

internal fun parseTaskScheduleFromForm(
    scheduleKind: TaskScheduleKindUi,
    onceAt: String,
    intervalMinutes: String,
    cronExpression: String,
    minimumIntervalMinutes: Long,
    now: Instant,
    zoneId: ZoneId,
): TaskSchedule =
    when (scheduleKind) {
        TaskScheduleKindUi.Once -> parseOnceSchedule(onceAt = onceAt, now = now)
        TaskScheduleKindUi.Interval ->
            parseIntervalSchedule(
                intervalMinutes = intervalMinutes,
                minimumIntervalMinutes = minimumIntervalMinutes,
                now = now,
            )
        TaskScheduleKindUi.Cron ->
            parseCronSchedule(
                cronExpression = cronExpression,
                now = now,
                zoneId = zoneId,
            )
    }

private fun parseOnceSchedule(
    onceAt: String,
    now: Instant,
): TaskSchedule.Once {
    val scheduledAt =
        try {
            Instant.parse(onceAt.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("Once time must be an ISO-8601 instant.")
        }
    require(scheduledAt.isAfter(now)) {
        "Once tasks must be scheduled in the future."
    }
    return TaskSchedule.Once(scheduledAt)
}

private fun parseIntervalSchedule(
    intervalMinutes: String,
    minimumIntervalMinutes: Long,
    now: Instant,
): TaskSchedule.Interval {
    require(minimumIntervalMinutes > 0L) { "Minimum interval must be positive." }
    val minutes =
        intervalMinutes
            .trim()
            .toLongOrNull()
            ?: throw IllegalArgumentException("Interval minutes must be a whole number.")
    require(minutes >= minimumIntervalMinutes) {
        "Intervals must be at least $minimumIntervalMinutes minutes."
    }
    require(minutes <= MAX_SAFE_DURATION_MINUTES) {
        "Intervals must be at most $MAX_SAFE_DURATION_MINUTES minutes."
    }
    return TaskSchedule.Interval(
        anchorAt = now,
        repeatEvery = Duration.ofMinutes(minutes),
    )
}

private fun parseCronSchedule(
    cronExpression: String,
    now: Instant,
    zoneId: ZoneId,
): TaskSchedule.Cron {
    val expression =
        try {
            CronExpression.parse(cronExpression.trim())
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid cron expression: ${error.message}")
        }
    val schedule = TaskSchedule.Cron(expression = expression, zoneId = zoneId)
    require(NextRunCalculator.computeNextRun(schedule, now) != null) {
        "Cron expression has no next run in the next year."
    }
    return schedule
}
