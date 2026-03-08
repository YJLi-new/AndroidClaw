package ai.androidclaw.runtime.scheduler

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class TaskExecutionMode {
    MainSession,
    IsolatedSession,
}

enum class TaskPrecisionMode {
    Approximate,
    PreciseUserVisible,
}

sealed interface TaskSchedule {
    data class Once(
        val at: Instant,
    ) : TaskSchedule

    data class Interval(
        val anchorAt: Instant,
        val repeatEvery: Duration,
    ) : TaskSchedule

    data class Cron(
        val expression: CronExpression,
        val zoneId: ZoneId,
    ) : TaskSchedule
}

data class SchedulerCapabilities(
    val minimumBackgroundInterval: Duration,
    val supportsExactAlarms: Boolean,
    val supportedKinds: List<String>,
)

