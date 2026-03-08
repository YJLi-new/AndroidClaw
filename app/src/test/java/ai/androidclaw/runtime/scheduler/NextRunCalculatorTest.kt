package ai.androidclaw.runtime.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class NextRunCalculatorTest {
    @Test
    fun `computes next interval run from anchor`() {
        val anchor = Instant.parse("2026-03-08T00:00:00Z")
        val next = NextRunCalculator.computeNextRun(
            schedule = TaskSchedule.Interval(
                anchorAt = anchor,
                repeatEvery = Duration.ofMinutes(15),
            ),
            after = Instant.parse("2026-03-08T00:31:00Z"),
        )

        assertEquals(Instant.parse("2026-03-08T00:45:00Z"), next)
    }

    @Test
    fun `computes next cron run`() {
        val next = NextRunCalculator.computeNextRun(
            schedule = TaskSchedule.Cron(
                expression = CronExpression.parse("@daily"),
                zoneId = ZoneId.of("UTC"),
            ),
            after = Instant.parse("2026-03-08T23:30:00Z"),
        )

        assertNotNull(next)
        assertEquals(Instant.parse("2026-03-09T00:00:00Z"), next)
    }
}
