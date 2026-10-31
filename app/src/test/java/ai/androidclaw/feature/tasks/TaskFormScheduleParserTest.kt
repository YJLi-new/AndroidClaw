package ai.androidclaw.feature.tasks

import ai.androidclaw.runtime.scheduler.MAX_SAFE_DURATION_MINUTES
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.testutil.text
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class TaskFormScheduleParserTest {
    private val now = Instant.parse("2026-03-08T00:00:00Z")
    private val utc = ZoneId.of("UTC")

    private fun draft(
        kind: TaskScheduleKindUi,
        onceDate: LocalDate? = null,
        onceTime: LocalTime? = null,
        intervalMinutes: String = "",
        cronExpression: String = "",
    ) = TaskScheduleDraft(kind, onceDate, onceTime, intervalMinutes, cronExpression)

    private fun parse(
        draft: TaskScheduleDraft,
        zoneId: ZoneId = utc,
        existing: TaskSchedule? = null,
    ): TaskScheduleParseResult = parseTaskSchedule(draft, minimumIntervalMinutes = 15L, now = now, zoneId = zoneId, existing = existing)

    private fun TaskScheduleParseResult.message(): String = (this as TaskScheduleParseResult.Invalid).toUiText(15L).text()

    private fun TaskScheduleParseResult.schedule(): TaskSchedule = (this as TaskScheduleParseResult.Valid).schedule

    @Test
    fun `parses valid interval form schedule`() {
        val schedule = parse(draft(TaskScheduleKindUi.Interval, intervalMinutes = " 60 ")).schedule() as TaskSchedule.Interval

        assertEquals(now, schedule.anchorAt)
        assertEquals(Duration.ofMinutes(60), schedule.repeatEvery)
    }

    @Test
    fun `editing an unchanged interval keeps its anchor`() {
        val anchor = Instant.parse("2026-01-01T09:00:00Z")
        val existing = TaskSchedule.Interval(anchorAt = anchor, repeatEvery = Duration.ofMinutes(60))

        val kept = parse(draft(TaskScheduleKindUi.Interval, intervalMinutes = "60"), existing = existing).schedule() as TaskSchedule.Interval
        val changed = parse(draft(TaskScheduleKindUi.Interval, intervalMinutes = "90"), existing = existing).schedule() as TaskSchedule.Interval

        assertEquals(anchor, kept.anchorAt)
        assertEquals(now, changed.anchorAt)
    }

    @Test
    fun `rejects non numeric interval form input with stable message`() {
        val result = parse(draft(TaskScheduleKindUi.Interval, intervalMinutes = "soon"))

        assertEquals(TaskScheduleError.IntervalNotNumber, (result as TaskScheduleParseResult.Invalid).error)
        assertEquals("Interval minutes must be a whole number.", result.message())
    }

    @Test
    fun `rejects too short interval form input`() {
        assertEquals("Intervals must be at least 15 minutes.", parse(draft(TaskScheduleKindUi.Interval, intervalMinutes = "5")).message())
    }

    @Test
    fun `rejects overflowing interval form input with stable message`() {
        val result = parse(draft(TaskScheduleKindUi.Interval, intervalMinutes = (MAX_SAFE_DURATION_MINUTES + 1L).toString()))

        assertEquals("Intervals must be at most $MAX_SAFE_DURATION_MINUTES minutes.", result.message())
    }

    @Test
    fun `rejects once schedule without a picked date or time`() {
        assertEquals("Pick a date and time.", parse(draft(TaskScheduleKindUi.Once, onceDate = LocalDate.of(2026, 3, 9))).message())
        assertEquals("Pick a date and time.", parse(draft(TaskScheduleKindUi.Once, onceTime = LocalTime.NOON)).message())
    }

    @Test
    fun `rejects past once form timestamp`() {
        val result = parse(draft(TaskScheduleKindUi.Once, onceDate = LocalDate.of(2026, 3, 7), onceTime = LocalTime.of(23, 59)))

        assertEquals("Once tasks must be scheduled in the future.", result.message())
    }

    @Test
    fun `once schedule converts the picked local time with the device zone`() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val schedule =
            parse(
                draft(TaskScheduleKindUi.Once, onceDate = LocalDate.of(2026, 3, 9), onceTime = LocalTime.of(9, 30)),
                zoneId = shanghai,
            ).schedule() as TaskSchedule.Once

        assertEquals(Instant.parse("2026-03-09T01:30:00Z"), schedule.at)
    }

    @Test
    fun `rejects invalid cron form with stable prefix`() {
        val result = parse(draft(TaskScheduleKindUi.Cron, cronExpression = "soon * * * *"))

        assertEquals("Invalid cron expression: Invalid cron value: 'soon'.", result.message())
    }

    @Test
    fun `rejects cron form with no next run in search horizon`() {
        assertEquals(
            "Cron expression has no next run in the next year.",
            parse(draft(TaskScheduleKindUi.Cron, cronExpression = "0 0 31 2 *")).message(),
        )
    }

    @Test
    fun `parses valid cron form schedule and previews the next runs`() {
        val schedule = parse(draft(TaskScheduleKindUi.Cron, cronExpression = "@daily")).schedule()

        assertTrue(schedule is TaskSchedule.Cron)
        val runs = nextRuns(schedule, now, count = 3)
        assertEquals(
            listOf(
                Instant.parse("2026-03-09T00:00:00Z"),
                Instant.parse("2026-03-10T00:00:00Z"),
                Instant.parse("2026-03-11T00:00:00Z"),
            ),
            runs,
        )
    }

    @Test
    fun `initial next run follows the schedule kind`() {
        val once = TaskSchedule.Once(Instant.parse("2026-03-09T00:00:00Z"))
        val futureAnchor = TaskSchedule.Interval(anchorAt = Instant.parse("2026-03-08T06:00:00Z"), repeatEvery = Duration.ofHours(1))
        val pastAnchor = TaskSchedule.Interval(anchorAt = Instant.parse("2026-03-07T23:30:00Z"), repeatEvery = Duration.ofHours(1))

        assertEquals(once.at, initialNextRun(once, now))
        assertEquals(futureAnchor.anchorAt, initialNextRun(futureAnchor, now))
        assertTrue(initialNextRun(pastAnchor, now)!!.isAfter(now))
    }

    @Test
    fun `interval presets map known minute values`() {
        assertEquals(IntervalPreset.Hourly, IntervalPreset.forMinutes(60))
        assertEquals(IntervalPreset.Daily, IntervalPreset.forMinutes(1_440))
        assertEquals(IntervalPreset.Custom, IntervalPreset.forMinutes(45))
    }

    @Test
    fun `rejects non positive minimum interval`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTaskSchedule(draft(TaskScheduleKindUi.Interval, intervalMinutes = "60"), 0L, now, utc)
        }
    }
}
