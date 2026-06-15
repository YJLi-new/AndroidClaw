package ai.androidclaw.feature.tasks

import ai.androidclaw.runtime.scheduler.MAX_SAFE_DURATION_MINUTES
import ai.androidclaw.runtime.scheduler.TaskSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TaskFormScheduleParserTest {
    private val now = Instant.parse("2026-03-08T00:00:00Z")

    @Test
    fun `parses valid interval form schedule`() {
        val schedule =
            parseTaskScheduleFromForm(
                scheduleKind = TaskScheduleKindUi.Interval,
                onceAt = "",
                intervalMinutes = " 60 ",
                cronExpression = "",
                minimumIntervalMinutes = 15L,
                now = now,
                zoneId = ZoneId.of("UTC"),
            ) as TaskSchedule.Interval

        assertEquals(now, schedule.anchorAt)
        assertEquals(Duration.ofMinutes(60), schedule.repeatEvery)
    }

    @Test
    fun `rejects non numeric interval form input with stable message`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                parseTaskScheduleFromForm(
                    scheduleKind = TaskScheduleKindUi.Interval,
                    onceAt = "",
                    intervalMinutes = "soon",
                    cronExpression = "",
                    minimumIntervalMinutes = 15L,
                    now = now,
                    zoneId = ZoneId.of("UTC"),
                )
            }

        assertEquals("Interval minutes must be a whole number.", exception.message)
    }

    @Test
    fun `rejects too short interval form input`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                parseTaskScheduleFromForm(
                    scheduleKind = TaskScheduleKindUi.Interval,
                    onceAt = "",
                    intervalMinutes = "5",
                    cronExpression = "",
                    minimumIntervalMinutes = 15L,
                    now = now,
                    zoneId = ZoneId.of("UTC"),
                )
            }

        assertEquals("Intervals must be at least 15 minutes.", exception.message)
    }

    @Test
    fun `rejects overflowing interval form input with stable message`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                parseTaskScheduleFromForm(
                    scheduleKind = TaskScheduleKindUi.Interval,
                    onceAt = "",
                    intervalMinutes = (MAX_SAFE_DURATION_MINUTES + 1L).toString(),
                    cronExpression = "",
                    minimumIntervalMinutes = 15L,
                    now = now,
                    zoneId = ZoneId.of("UTC"),
                )
            }

        assertEquals(
            "Intervals must be at most $MAX_SAFE_DURATION_MINUTES minutes.",
            exception.message,
        )
    }

    @Test
    fun `rejects invalid once form timestamp with stable message`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                parseTaskScheduleFromForm(
                    scheduleKind = TaskScheduleKindUi.Once,
                    onceAt = "tomorrow morning",
                    intervalMinutes = "",
                    cronExpression = "",
                    minimumIntervalMinutes = 15L,
                    now = now,
                    zoneId = ZoneId.of("UTC"),
                )
            }

        assertEquals("Once time must be an ISO-8601 instant.", exception.message)
    }

    @Test
    fun `rejects past once form timestamp`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                parseTaskScheduleFromForm(
                    scheduleKind = TaskScheduleKindUi.Once,
                    onceAt = "2026-03-07T23:59:00Z",
                    intervalMinutes = "",
                    cronExpression = "",
                    minimumIntervalMinutes = 15L,
                    now = now,
                    zoneId = ZoneId.of("UTC"),
                )
            }

        assertEquals("Once tasks must be scheduled in the future.", exception.message)
    }

    @Test
    fun `rejects invalid cron form with stable prefix`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                parseTaskScheduleFromForm(
                    scheduleKind = TaskScheduleKindUi.Cron,
                    onceAt = "",
                    intervalMinutes = "",
                    cronExpression = "soon * * * *",
                    minimumIntervalMinutes = 15L,
                    now = now,
                    zoneId = ZoneId.of("UTC"),
                )
            }

        assertEquals("Invalid cron expression: Invalid cron value: 'soon'.", exception.message)
    }

    @Test
    fun `rejects cron form with no next run in search horizon`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                parseTaskScheduleFromForm(
                    scheduleKind = TaskScheduleKindUi.Cron,
                    onceAt = "",
                    intervalMinutes = "",
                    cronExpression = "0 0 31 2 *",
                    minimumIntervalMinutes = 15L,
                    now = now,
                    zoneId = ZoneId.of("UTC"),
                )
            }

        assertEquals("Cron expression has no next run in the next year.", exception.message)
    }

    @Test
    fun `parses valid cron form schedule`() {
        val schedule =
            parseTaskScheduleFromForm(
                scheduleKind = TaskScheduleKindUi.Cron,
                onceAt = "",
                intervalMinutes = "",
                cronExpression = "@daily",
                minimumIntervalMinutes = 15L,
                now = now,
                zoneId = ZoneId.of("UTC"),
            )

        assertTrue(schedule is TaskSchedule.Cron)
    }
}
