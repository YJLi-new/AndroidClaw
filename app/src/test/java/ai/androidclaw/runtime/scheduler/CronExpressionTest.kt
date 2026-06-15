package ai.androidclaw.runtime.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun `normalizes Sunday seven to zero`() {
        val expression = CronExpression.parse("0 0 * * 7")
        val sunday = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ZoneId.of("UTC"))
        val monday = ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, ZoneId.of("UTC"))

        assertTrue(expression.matches(sunday))
        assertFalse(expression.matches(monday))
    }

    @Test
    fun `rejects malformed numeric fields with stable messages`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                CronExpression.parse("soon * * * *")
            }

        assertEquals("Invalid cron value: 'soon'.", exception.message)
    }

    @Test
    fun `rejects malformed step fields with stable messages`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                CronExpression.parse("*/soon * * * *")
            }

        assertEquals("Invalid cron step: 'soon'.", exception.message)
    }

    @Test
    fun `rejects empty cron list items`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                CronExpression.parse("0,,30 * * * *")
            }

        assertEquals("Cron field cannot contain empty list items.", exception.message)
    }

    @Test
    fun `rejects repeated step separators`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                CronExpression.parse("*/5/2 * * * *")
            }

        assertEquals("Cron part cannot contain multiple step separators.", exception.message)
    }
}
