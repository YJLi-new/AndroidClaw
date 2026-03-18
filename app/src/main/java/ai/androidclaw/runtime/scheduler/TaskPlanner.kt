package ai.androidclaw.runtime.scheduler

import ai.androidclaw.data.model.Task
import java.time.Duration
import java.time.Instant

class TaskPlanner(
    private val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS,
) {
    fun initialScheduleAt(
        task: Task,
        now: Instant,
    ): Instant? = task.nextRunAt ?: nextScheduledRun(task, now)

    fun nextScheduledRun(
        task: Task,
        after: Instant,
    ): Instant? = NextRunCalculator.computeNextRun(task.schedule, after)

    fun nextRetryAt(
        task: Task,
        failureCount: Int,
        after: Instant,
    ): Instant? {
        if (failureCount <= 0 || failureCount > task.maxRetries) {
            return null
        }
        val retryDelay = retryDelays[minOf(failureCount - 1, retryDelays.lastIndex)]
        return after.plus(retryDelay)
    }

    fun selectNextRun(
        nextScheduledAt: Instant?,
        retryAt: Instant?,
    ): Instant? =
        when {
            nextScheduledAt == null -> retryAt
            retryAt == null -> nextScheduledAt
            nextScheduledAt.isBefore(retryAt) -> nextScheduledAt
            else -> retryAt
        }

    companion object {
        val DEFAULT_RETRY_DELAYS: List<Duration> =
            listOf(
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15),
                Duration.ofHours(1),
                Duration.ofHours(3),
                Duration.ofHours(6),
            )
    }
}
