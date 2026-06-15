package ai.androidclaw.data.model

import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.TaskSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class ScheduleSerializerTest {
    @Test
    fun `round trip once schedule`() {
        val schedule = TaskSchedule.Once(at = Instant.ofEpochMilli(1_234L))

        val encoded = ScheduleSerializer.toJson(schedule)

        assertEquals(schedule, ScheduleSerializer.fromJson(encoded))
        assertEquals(schedule, ScheduleSerializer.fromJsonOrNull(encoded))
    }

    @Test
    fun `round trip interval schedule`() {
        val schedule =
            TaskSchedule.Interval(
                anchorAt = Instant.ofEpochMilli(9_876L),
                repeatEvery = Duration.ofMinutes(15),
            )

        val encoded = ScheduleSerializer.toJson(schedule)

        assertEquals(schedule, ScheduleSerializer.fromJson(encoded))
    }

    @Test
    fun `rejects zero interval schedules before persistence`() {
        val schedule =
            TaskSchedule.Interval(
                anchorAt = Instant.ofEpochMilli(9_876L),
                repeatEvery = Duration.ZERO,
            )

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                ScheduleSerializer.toJson(schedule)
            }

        assertEquals("Interval schedule requires a positive intervalMillis.", exception.message)
    }

    @Test
    fun `round trip cron schedule`() {
        val schedule =
            TaskSchedule.Cron(
                expression = CronExpression.parse("0 12 1 * 1,3"),
                zoneId = ZoneId.of("UTC"),
            )

        val encoded = ScheduleSerializer.toJson(schedule)

        assertEquals(schedule, ScheduleSerializer.fromJson(encoded))
    }

    @Test
    fun `rejects zero interval persisted schedules`() {
        val raw =
            """
            {"kind":"interval","anchorAtEpochMillis":0,"intervalMillis":0}
            """.trimIndent()

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                ScheduleSerializer.fromJson(raw)
            }

        assertEquals("Interval schedule requires a positive intervalMillis.", exception.message)
        assertNull(ScheduleSerializer.fromJsonOrNull(raw))
    }

    @Test
    fun `nullable schedule decoding returns null for malformed json`() {
        val raw = """{"kind":"interval","anchorAtEpochMillis":"""

        assertNull(ScheduleSerializer.fromJsonOrNull(raw))
    }

    @Test
    fun `rejects negative interval persisted schedules`() {
        val raw =
            """
            {"kind":"interval","anchorAtEpochMillis":0,"intervalMillis":-1}
            """.trimIndent()

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                ScheduleSerializer.fromJson(raw)
            }

        assertEquals("Interval schedule requires a positive intervalMillis.", exception.message)
    }

    @Test
    fun `rejects missing required schedule fields with stable message`() {
        val raw =
            """
            {"kind":"once"}
            """.trimIndent()

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                ScheduleSerializer.fromJson(raw)
            }

        assertEquals("Schedule field 'atEpochMillis' is required.", exception.message)
    }

    @Test
    fun `rejects blank cron zone id`() {
        val raw =
            """
            {"kind":"cron","cronExpr":"@daily","zoneId":"   "}
            """.trimIndent()

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                ScheduleSerializer.fromJson(raw)
            }

        assertEquals("Schedule field 'zoneId' cannot be blank.", exception.message)
    }
}
