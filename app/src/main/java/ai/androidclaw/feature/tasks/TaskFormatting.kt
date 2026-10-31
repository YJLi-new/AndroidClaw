package ai.androidclaw.feature.tasks

import ai.androidclaw.R
import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.ui.components.NoticeTone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.time.format.DateTimeFormatter

@Composable
@ReadOnlyComposable
internal fun intervalLabel(minutes: Long): String =
    when {
        minutes > 0 && minutes % 1_440L == 0L -> {
            val days = (minutes / 1_440L).toInt()
            pluralStringResource(R.plurals.tasks_every_days, days, days)
        }
        minutes > 0 && minutes % 60L == 0L -> {
            val hours = (minutes / 60L).toInt()
            pluralStringResource(R.plurals.tasks_every_hours, hours, hours)
        }
        else -> pluralStringResource(R.plurals.tasks_every_minutes, minutes.toInt(), minutes.toInt())
    }

@Composable
internal fun scheduleSummary(
    schedule: TaskSchedule,
    formatter: DateTimeFormatter,
): String =
    when (schedule) {
        is TaskSchedule.Once -> stringResource(R.string.tasks_schedule_once, formatter.format(schedule.at))
        is TaskSchedule.Interval -> intervalLabel(schedule.repeatEvery.toMinutes())
        is TaskSchedule.Cron -> stringResource(R.string.tasks_schedule_cron, schedule.expression.toEditableText())
    }

@Composable
@ReadOnlyComposable
internal fun executionModeLabel(mode: TaskExecutionMode): String =
    when (mode) {
        TaskExecutionMode.MainSession -> stringResource(R.string.tasks_mode_post_to_session)
        TaskExecutionMode.IsolatedSession -> stringResource(R.string.tasks_mode_isolated)
    }

@Composable
@ReadOnlyComposable
internal fun runStatusLabel(status: TaskRunStatus): String =
    when (status) {
        TaskRunStatus.Pending -> stringResource(R.string.tasks_run_pending)
        TaskRunStatus.Running -> stringResource(R.string.tasks_run_running)
        TaskRunStatus.Success -> stringResource(R.string.tasks_run_success)
        TaskRunStatus.Failure -> stringResource(R.string.tasks_run_failure)
        TaskRunStatus.Skipped -> stringResource(R.string.tasks_run_skipped)
    }

internal fun runStatusTone(status: TaskRunStatus): NoticeTone =
    when (status) {
        TaskRunStatus.Success -> NoticeTone.Success
        TaskRunStatus.Failure -> NoticeTone.Error
        TaskRunStatus.Skipped -> NoticeTone.Warning
        TaskRunStatus.Pending, TaskRunStatus.Running -> NoticeTone.Info
    }

/** Cron text for display and editing (lists are expanded, like the stored spec). */
internal fun CronExpression.toEditableText(): String =
    listOf(minute, hour, dayOfMonth, month, dayOfWeek).joinToString(" ") { field ->
        if (field.isWildcard) "*" else field.allowed.sorted().compactRanges()
    }

private fun List<Int>.compactRanges(): String {
    if (isEmpty()) return "*"
    val parts = mutableListOf<String>()
    var start = first()
    var previous = first()
    for (value in drop(1)) {
        if (value == previous + 1) {
            previous = value
            continue
        }
        parts += if (previous - start >= 2) "$start-$previous" else (start..previous).joinToString(",")
        start = value
        previous = value
    }
    parts += if (previous - start >= 2) "$start-$previous" else (start..previous).joinToString(",")
    return parts.joinToString(",")
}
