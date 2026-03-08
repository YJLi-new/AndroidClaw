package ai.androidclaw.runtime.scheduler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CronExpressionTest {
    @Test
    fun `matches daily macro at midnight`() {
        val expression = CronExpression.parse("@daily")
        val matching = ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, ZoneId.of("UTC"))
        val nonMatching = matching.plusHours(1)

        assertTrue(expression.matches(matching))
        assertFalse(expression.matches(nonMatching))
    }

    @Test
    fun `matches weekday morning cron`() {
        val expression = CronExpression.parse("0 9 * * 1-5")
        val monday = ZonedDateTime.of(2026, 3, 9, 9, 0, 0, 0, ZoneId.of("UTC"))
        val sunday = ZonedDateTime.of(2026, 3, 8, 9, 0, 0, 0, ZoneId.of("UTC"))

        assertTrue(expression.matches(monday))
        assertFalse(expression.matches(sunday))
    }
}

