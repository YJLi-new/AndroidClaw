package ai.androidclaw.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatTranscriptGroupingTest {
    private val now = Instant.parse("2026-03-19T00:00:00Z")

    private fun message(
        id: String,
        role: ChatRole,
        text: String,
        toolCallId: String? = null,
    ) = ChatMessageUi(id = id, role = role, text = text, createdAt = now, toolCallId = toolCallId)

    @Test
    fun `tool request and its result collapse into one card`() {
        val items =
            groupChatItems(
                listOf(
                    message("u1", ChatRole.User, "what time is it"),
                    message("c1", ChatRole.ToolCall, "Tool request: some.tool {\"zone\":\"UTC\"}", toolCallId = "call-1"),
                    message("r1", ChatRole.ToolResult, "Tool result: 12:00", toolCallId = "call-1"),
                    message("a1", ChatRole.Assistant, "It is noon."),
                ),
            )

        assertEquals(3, items.size)
        val tool = items[1] as ChatItemUi.Tool
        assertEquals("some.tool", tool.toolName)
        assertEquals("{\"zone\":\"UTC\"}", tool.arguments)
        assertEquals("12:00", tool.result)
        assertEquals(listOf("c1", "r1"), tool.messageIds)
        assertTrue(items[0] is ChatItemUi.Message)
        assertTrue(items[2] is ChatItemUi.Message)
    }

    @Test
    fun `tool request without a result stays pending`() {
        val items = groupChatItems(listOf(message("c1", ChatRole.ToolCall, "Tool request: other.tool", toolCallId = "call-9")))

        val tool = items.single() as ChatItemUi.Tool
        assertEquals("other.tool", tool.toolName)
        assertEquals("", tool.arguments)
        assertNull(tool.result)
    }

    @Test
    fun `orphan tool result renders as its own card`() {
        val items = groupChatItems(listOf(message("r1", ChatRole.ToolResult, "Tool result: done", toolCallId = "missing")))

        val tool = items.single() as ChatItemUi.Tool
        assertEquals("", tool.toolName)
        assertEquals("done", tool.result)
    }

    @Test
    fun `tool request parsing tolerates unprefixed and blank content`() {
        assertEquals("" to "", parseToolRequest("Tool request:   "))
        assertEquals("plain.name" to "", parseToolRequest("plain.name"))
        assertEquals("call" to "(x)", parseToolRequest("Tool request: call(x)"))
    }

    @Test
    fun `transcript window grows to include a highlighted search hit`() {
        val messages = (0 until 400).map { index -> message("m$index", ChatRole.User, "msg $index") }

        assertEquals(150, transcriptWindow(messages, requestedWindow = 150, highlightedMessageId = null))
        assertEquals(10, transcriptWindow(messages.take(10), requestedWindow = 150, highlightedMessageId = null))
        val window = transcriptWindow(messages, requestedWindow = 150, highlightedMessageId = "m20")
        assertTrue(window >= 400 - 20)
        assertTrue(window <= messages.size)
        assertEquals(150, transcriptWindow(messages, requestedWindow = 150, highlightedMessageId = "unknown"))
    }
}
