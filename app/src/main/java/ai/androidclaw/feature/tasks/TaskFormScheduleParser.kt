package ai.androidclaw.feature.tasks

import ai.androidclaw.R
import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.MAX_SAFE_DURATION_MINUTES
import ai.androidclaw.runtime.scheduler.NextRunCalculator
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.ui.text.UiText
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class TaskScheduleKindUi {
    Once,
    Interval,
    Cron,
}

/** Interval presets offered by the editor; [Custom] reveals a minutes field. */
enum class IntervalPreset(
    val minutes: Long?,
) {
    FifteenMinutes(15),
    Hourly(60),
    Daily(1_440),
    Custom(null),
    ;

    companion object {
        fun forMinutes(minutes: Long): IntervalPreset = entries.firstOrNull { it.minutes == minutes } ?: Custom
    }
}

enum class TaskScheduleError(
    val messageRes: Int,
) {
    OnceMissing(R.string.tasks_error_once_missing),
    OnceInPast(R.string.tasks_error_once_past),
    IntervalNotNumber(R.string.tasks_error_interval_number),
    IntervalTooShort(R.string.tasks_error_interval_short),
    IntervalTooLong(R.string.tasks_error_interval_long),
    CronInvalid(R.string.tasks_error_cron_invalid),
    CronNoNextRun(R.string.tasks_error_cron_no_next_run),
}

sealed interface TaskScheduleParseResult {
    data class Valid(
        val schedule: TaskSchedule,
    ) : TaskScheduleParseResult

    data class Invalid(
        val error: TaskScheduleError,
        val detail: String? = null,
    ) : TaskScheduleParseResult {
        fun toUiText(minimumIntervalMinutes: Long): UiText =
            when (error) {
                TaskScheduleError.IntervalTooShort -> UiText.res(error.messageRes, minimumIntervalMinutes)
                TaskScheduleError.IntervalTooLong -> UiText.res(error.messageRes, MAX_SAFE_DURATION_MINUTES)
                TaskScheduleError.CronInvalid -> UiText.res(error.messageRes, detail.orEmpty())
                else -> UiText.res(error.messageRes)
            }
    }
}

/** Everything the editor collects for a schedule, in the user's terms. */
data class TaskScheduleDraft(
    val kind: TaskScheduleKindUi,
    val onceDate: LocalDate?,
    val onceTime: LocalTime?,
    val intervalMinutes: String,
    val cronExpression: String,
)

/**
 * Converts the editor draft into a [TaskSchedule]. One-off times are picked in
 * the device time zone ([zoneId]) and stored as UTC instants, as before.
 */
internal fun parseTaskSchedule(
    draft: TaskScheduleDraft,
    minimumIntervalMinutes: Long,
    now: Instant,
    zoneId: ZoneId,
    existing: TaskSchedule? = null,
): TaskScheduleParseResult =
    when (draft.kind) {
        TaskScheduleKindUi.Once -> parseOnce(draft, now, zoneId)
        TaskScheduleKindUi.Interval -> parseInterval(draft.intervalMinutes, minimumIntervalMinutes, now, existing)
        TaskScheduleKindUi.Cron -> parseCron(draft.cronExpression, now, zoneId)
    }

private fun parseOnce(
    draft: TaskScheduleDraft,
    now: Instant,
    zoneId: ZoneId,
): TaskScheduleParseResult {
    val date = draft.onceDate ?: return TaskScheduleParseResult.Invalid(TaskScheduleError.OnceMissing)
    val time = draft.onceTime ?: return TaskScheduleParseResult.Invalid(TaskScheduleError.OnceMissing)
    val at = date.atTime(time).atZone(zoneId).toInstant()
    if (!at.isAfter(now)) {
        return TaskScheduleParseResult.Invalid(TaskScheduleError.OnceInPast)
    }
    return TaskScheduleParseResult.Valid(TaskSchedule.Once(at))
}

private fun parseInterval(
    intervalMinutes: String,
    minimumIntervalMinutes: Long,
    now: Instant,
    existing: TaskSchedule?,
): TaskScheduleParseResult {
    require(minimumIntervalMinutes > 0L) { "Minimum interval must be positive." }
    val minutes =
        intervalMinutes.trim().toLongOrNull()
            ?: return TaskScheduleParseResult.Invalid(TaskScheduleError.IntervalNotNumber)
    if (minutes < minimumIntervalMinutes) {
        return TaskScheduleParseResult.Invalid(TaskScheduleError.IntervalTooShort)
    }
    if (minutes > MAX_SAFE_DURATION_MINUTES) {
        return TaskScheduleParseResult.Invalid(TaskScheduleError.IntervalTooLong)
    }
    val repeatEvery = Duration.ofMinutes(minutes)
    // Keep the original anchor when an edit leaves the interval unchanged.
    val anchor =
        (existing as? TaskSchedule.Interval)
            ?.takeIf { it.repeatEvery == repeatEvery }
            ?.anchorAt
            ?: now
    return TaskScheduleParseResult.Valid(TaskSchedule.Interval(anchorAt = anchor, repeatEvery = repeatEvery))
}

private fun parseCron(
    cronExpression: String,
    now: Instant,
    zoneId: ZoneId,
): TaskScheduleParseResult {
    val expression =
        try {
            CronExpression.parse(cronExpression.trim())
        } catch (error: IllegalArgumentException) {
            return TaskScheduleParseResult.Invalid(TaskScheduleError.CronInvalid, error.message)
        }
    val schedule = TaskSchedule.Cron(expression = expression, zoneId = zoneId)
    if (NextRunCalculator.computeNextRun(schedule, now) == null) {
        return TaskScheduleParseResult.Invalid(TaskScheduleError.CronNoNextRun)
    }
    return TaskScheduleParseResult.Valid(schedule)
}

/** The next [count] run times of [schedule] after [now] (for the editor preview). */
internal fun nextRuns(
    schedule: TaskSchedule,
    now: Instant,
    count: Int,
): List<Instant> {
    val runs = ArrayList<Instant>(count)
    var cursor = now
    repeat(count) {
        val next = NextRunCalculator.computeNextRun(schedule, cursor) ?: return runs
        runs += next
        cursor = next
    }
    return runs
}

/** Initial next run for a freshly saved schedule (mirrors repository creation). */
internal fun initialNextRun(
    schedule: TaskSchedule,
    now: Instant,
): Instant? =
    when (schedule) {
        is TaskSchedule.Once -> schedule.at
        is TaskSchedule.Interval ->
            if (schedule.anchorAt.isAfter(now)) schedule.anchorAt else NextRunCalculator.computeNextRun(schedule, now)
        is TaskSchedule.Cron -> NextRunCalculator.computeNextRun(schedule, now)
    }
