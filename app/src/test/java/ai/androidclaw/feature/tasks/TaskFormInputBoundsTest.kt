package ai.androidclaw.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskFormInputBoundsTest {
    @Test
    fun `leaves in-budget form text unchanged`() {
        val bounded = boundTaskFormInput("daily summary", maxChars = 80)

        assertEquals("daily summary", bounded.value)
        assertFalse(bounded.wasTruncated)
    }

    @Test
    fun `truncates oversized form text at the requested budget`() {
        val bounded = boundTaskFormInput("abcdef", maxChars = 4)

        assertEquals("abcd", bounded.value)
        assertTrue(bounded.wasTruncated)
    }

    @Test
    fun `schedule field budgets bound pasted text`() {
        val interval = boundTaskFormInput("9".repeat(TASK_FORM_INTERVAL_MINUTES_MAX_CHARS + 10), TASK_FORM_INTERVAL_MINUTES_MAX_CHARS)
        val cron = boundTaskFormInput("*".repeat(TASK_FORM_CRON_EXPRESSION_MAX_CHARS + 10), TASK_FORM_CRON_EXPRESSION_MAX_CHARS)

        assertEquals(TASK_FORM_INTERVAL_MINUTES_MAX_CHARS, interval.value.length)
        assertTrue(interval.wasTruncated)
        assertEquals(TASK_FORM_CRON_EXPRESSION_MAX_CHARS, cron.value.length)
        assertTrue(cron.wasTruncated)
    }

    @Test
    fun `rejects invalid form text budgets`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                boundTaskFormInput("value", maxChars = 0)
            }

        assertEquals("Task form input max must be positive.", exception.message)
    }
}
