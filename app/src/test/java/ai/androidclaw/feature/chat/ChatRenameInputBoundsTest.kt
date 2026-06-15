package ai.androidclaw.feature.chat

import ai.androidclaw.data.repository.SESSION_TITLE_MAX_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenameInputBoundsTest {
    @Test
    fun `leaves in-budget rename input unchanged`() {
        val bounded = boundChatRenameInput("Planning")

        assertEquals("Planning", bounded.value)
        assertFalse(bounded.wasTruncated)
    }

    @Test
    fun `truncates oversized rename input at session title budget`() {
        val oversizedTitle = "t".repeat(SESSION_TITLE_MAX_CHARS + 20)

        val bounded = boundChatRenameInput(oversizedTitle)

        assertEquals(SESSION_TITLE_MAX_CHARS, bounded.value.length)
        assertEquals(oversizedTitle.take(SESSION_TITLE_MAX_CHARS), bounded.value)
        assertTrue(bounded.wasTruncated)
    }
}
