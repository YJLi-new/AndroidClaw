package ai.androidclaw.data.model

import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.TaskSchedule
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleSerializerTest {
    @Test
    fun `round trip once schedule`() {
        val schedule = TaskSchedule.Once(at = Instant.ofEpochMilli(1_234L))

        val encoded = ScheduleSerializer.toJson(schedule)

        assertEquals(schedule, ScheduleSerializer.fromJson(encoded))
    }

    @Test
    fun `round trip interval schedule`() {
        val schedule = TaskSchedule.Interval(
            anchorAt = Instant.ofEpochMilli(9_876L),
            repeatEvery = Duration.ofMinutes(15),
        )

        val encoded = ScheduleSerializer.toJson(schedule)

        assertEquals(schedule, ScheduleSerializer.fromJson(encoded))
    }

    @Test
    fun `round trip cron schedule`() {
        val schedule = TaskSchedule.Cron(
            expression = CronExpression.parse("0 12 1 * 1,3"),
            zoneId = ZoneId.of("UTC"),
        )

        val encoded = ScheduleSerializer.toJson(schedule)

        assertEquals(schedule, ScheduleSerializer.fromJson(encoded))
    }
}
