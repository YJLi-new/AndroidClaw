package ai.androidclaw.runtime.scheduler

import ai.androidclaw.data.model.Task
import java.time.Instant
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class TaskPlannerTest {
    private val planner = TaskPlanner()

    @Test
    fun `initialScheduleAt prefers persisted nextRunAt`() {
        val task = task(
            schedule = TaskSchedule.Once(Instant.parse("2026-03-10T00:00:00Z")),
            nextRunAt = Instant.parse("2026-03-09T06:00:00Z"),
        )

        val scheduledAt = planner.initialScheduleAt(
            task = task,
            now = Instant.parse("2026-03-09T00:00:00Z"),
        )

        assertEquals(Instant.parse("2026-03-09T06:00:00Z"), scheduledAt)
    }

    @Test
    fun `nextRetryAt uses capped discrete backoff`() {
        val task = task(maxRetries = 6)

        assertEquals(
            Instant.parse("2026-03-09T00:01:00Z"),
            planner.nextRetryAt(task, failureCount = 1, after = Instant.parse("2026-03-09T00:00:00Z")),
        )
        assertEquals(
            Instant.parse("2026-03-09T06:00:00Z"),
            planner.nextRetryAt(task, failureCount = 6, after = Instant.parse("2026-03-09T00:00:00Z")),
        )
        assertNull(
            planner.nextRetryAt(task, failureCount = 7, after = Instant.parse("2026-03-09T00:00:00Z")),
        )
    }

    @Test
    fun `selectNextRun prefers the earlier of scheduled and retry times`() {
        assertEquals(
            Instant.parse("2026-03-09T00:05:00Z"),
            planner.selectNextRun(
                nextScheduledAt = Instant.parse("2026-03-09T00:05:00Z"),
                retryAt = Instant.parse("2026-03-09T00:15:00Z"),
            ),
        )
        assertEquals(
            Instant.parse("2026-03-09T00:01:00Z"),
            planner.selectNextRun(
                nextScheduledAt = null,
                retryAt = Instant.parse("2026-03-09T00:01:00Z"),
            ),
        )
    }
}

private fun task(
    schedule: TaskSchedule = TaskSchedule.Interval(
        anchorAt = Instant.parse("2026-03-09T00:00:00Z"),
        repeatEvery = java.time.Duration.ofHours(1),
    ),
    nextRunAt: Instant? = null,
    maxRetries: Int = 3,
): Task {
    return Task(
        id = "task-1",
        name = "Task 1",
        prompt = "Run task",
        schedule = schedule,
        executionMode = TaskExecutionMode.MainSession,
        targetSessionId = "main",
        enabled = true,
        precise = false,
        nextRunAt = nextRunAt,
        lastRunAt = null,
        failureCount = 0,
        maxRetries = maxRetries,
        createdAt = Instant.parse("2026-03-09T00:00:00Z"),
        updatedAt = Instant.parse("2026-03-09T00:00:00Z"),
    )
}
