package ai.androidclaw.runtime.scheduler

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

object NextRunCalculator {
    fun computeNextRun(schedule: TaskSchedule, after: Instant): Instant? {
        return when (schedule) {
            is TaskSchedule.Once -> schedule.at.takeIf { it.isAfter(after) }
            is TaskSchedule.Interval -> computeIntervalNextRun(schedule, after)
            is TaskSchedule.Cron -> computeCronNextRun(schedule, after)
        }
    }

    private fun computeIntervalNextRun(schedule: TaskSchedule.Interval, after: Instant): Instant {
        if (after.isBefore(schedule.anchorAt)) return schedule.anchorAt
        val elapsedMillis = Duration.between(schedule.anchorAt, after).toMillis()
        val intervalMillis = schedule.repeatEvery.toMillis()
        val completedIntervals = (elapsedMillis / intervalMillis) + 1
        return schedule.anchorAt.plusMillis(completedIntervals * intervalMillis)
    }

    private fun computeCronNextRun(schedule: TaskSchedule.Cron, after: Instant): Instant? {
        var candidate = after.atZone(schedule.zoneId)
            .truncatedTo(ChronoUnit.MINUTES)
            .plusMinutes(1)
        repeat(366 * 24 * 60) {
            if (schedule.expression.matches(candidate)) {
                return candidate.toInstant()
            }
            candidate = candidate.plusMinutes(1)
        }
        return null
    }
}

