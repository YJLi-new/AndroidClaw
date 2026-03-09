package ai.androidclaw.runtime.scheduler

import ai.androidclaw.data.model.Task
import android.app.usage.UsageStatsManager

data class SchedulerDiagnostics(
    val supportsExactAlarms: Boolean = false,
    val exactAlarmGranted: Boolean = false,
    val standbyBucket: StandbyBucketInfo? = null,
) {
    val isRestrictedBucket: Boolean
        get() = standbyBucket?.isRestricted == true
}

data class StandbyBucketInfo(
    val value: Int,
    val label: String,
) {
    val isRestricted: Boolean
        get() = value == UsageStatsManager.STANDBY_BUCKET_RESTRICTED
}

enum class TaskSchedulingPath {
    WorkManagerApproximate,
    ExactAlarm,
}

data class TaskSchedulingDecision(
    val path: TaskSchedulingPath,
    val degradedReason: String? = null,
)

val Task.precisionMode: TaskPrecisionMode
    get() = if (precise) {
        TaskPrecisionMode.PreciseUserVisible
    } else {
        TaskPrecisionMode.Approximate
    }

fun Task.schedulingDecision(diagnostics: SchedulerDiagnostics): TaskSchedulingDecision {
    if (precisionMode == TaskPrecisionMode.Approximate) {
        return TaskSchedulingDecision(path = TaskSchedulingPath.WorkManagerApproximate)
    }
    if (!diagnostics.supportsExactAlarms) {
        return TaskSchedulingDecision(
            path = TaskSchedulingPath.WorkManagerApproximate,
            degradedReason = "Exact alarms are unavailable on this device; falling back to approximate.",
        )
    }
    if (!diagnostics.exactAlarmGranted) {
        return TaskSchedulingDecision(
            path = TaskSchedulingPath.WorkManagerApproximate,
            degradedReason = "Exact alarm permission denied; falling back to approximate.",
        )
    }
    return TaskSchedulingDecision(path = TaskSchedulingPath.ExactAlarm)
}

fun standbyBucketLabel(bucket: Int): String {
    return when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
        else -> "unknown($bucket)"
    }
}

fun workStopReasonLabel(stopReason: Int): String {
    return when {
        stopReason == 0 -> "not_stopped"
        stopReason > 0 -> "code($stopReason)"
        else -> "unknown($stopReason)"
    }
}
