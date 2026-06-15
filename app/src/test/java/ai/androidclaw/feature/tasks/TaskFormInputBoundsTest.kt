package ai.androidclaw.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `clears only task form truncation messages`() {
        assertNull(TASK_FORM_INPUT_TRUNCATED_MESSAGE.clearTaskFormTruncationMessage())
        assertEquals("Task name and prompt are required.", "Task name and prompt are required.".clearTaskFormTruncationMessage())
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
