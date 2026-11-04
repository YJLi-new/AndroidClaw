package ai.androidclaw.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatToolFormatTest {
    private val now = Instant.parse("2026-10-31T00:00:00Z")

    @Test
    fun `summary line is the headline and payload json stays separate`() {
        val view = parseToolResultView("Battery 80%, charging.\n{\"battery\":{\"level\":80},\"network\":\"wifi\"}")

        assertEquals("Battery 80%, charging.", view.headline)
        assertEquals("Battery 80%, charging.", view.summary)
        assertNull(view.errorCode)
        assertTrue(view.payload!!.contains("\"level\": 80"))
        assertTrue(view.payload!!.contains('\n'))
    }

    @Test
    fun `multi line summary keeps every line and failed results expose the error code`() {
        val view =
            parseToolResultView(
                "Memory is turned off in Settings.\nKeep notes in MEMORY.md.\n{\"errorCode\":\"MEMORY_DISABLED\"}",
            )

        assertEquals("Memory is turned off in Settings.", view.headline)
        assertEquals("Memory is turned off in Settings.\nKeep notes in MEMORY.md.", view.summary)
        assertEquals("MEMORY_DISABLED", view.errorCode)
    }

    @Test
    fun `plain text results without json are kept whole`() {
        val view = parseToolResultView("12:00")

        assertEquals("12:00", view.headline)
        assertNull(view.payload)
        assertNull(view.errorCode)
        assertEquals("{broken", parseToolResultView("Done\n{broken").summary.lines().last())
    }

    @Test
    fun `secrets in arguments and payloads are masked`() {
        val arguments = formatToolArguments("{\"url\":\"https://x.test\",\"apiKey\":\"sk-123\",\"nested\":{\"access_token\":\"t\"}}")
        assertFalse(arguments.contains("sk-123"))
        assertFalse(arguments.contains("\"t\""))
        assertTrue(arguments.contains(TOOL_REDACTED_VALUE))
        assertTrue(arguments.contains("https://x.test"))

        val view = parseToolResultView("ok\n{\"password\":\"hunter2\",\"inputTokens\":12}")
        assertFalse(view.payload!!.contains("hunter2"))
        assertTrue(view.payload!!.contains("12"))
        assertEquals("not json", formatToolArguments("not json"))
    }

    @Test
    fun `assistant echo of a direct tool result is not rendered twice`() {
        val items =
            groupChatItems(
                listOf(
                    ChatMessageUi("u1", ChatRole.User, "/list_tasks", now),
                    ChatMessageUi("c1", ChatRole.ToolCall, "Tool request: tasks_list {}", now, toolCallId = "k"),
                    ChatMessageUi("r1", ChatRole.ToolResult, "Tool result: Found 2 tasks.\n- a\n- b\n{\"count\":2}", now, toolCallId = "k"),
                    ChatMessageUi("a1", ChatRole.Assistant, "Found 2 tasks.\n\nActive skills: list_tasks", now),
                    ChatMessageUi("a2", ChatRole.Assistant, "Anything else?\n\nActive skills: list_tasks", now),
                ),
            )

        assertEquals(3, items.size)
        assertTrue(items[1] is ChatItemUi.Tool)
        assertEquals("Anything else?", (items[2] as ChatItemUi.Message).message.text)
    }

    @Test
    fun `attachment image links are dropped and attachment links keep their label`() {
        val id = "a6ec0d24-a574-4a23-bf51-365e33180691"

        assertEquals("Here it is:", "Here it is:\n\n![日出时的灯塔水彩画](attachment:$id)\n".withoutAttachmentLinks())
        assertEquals("", "![](Attachment://$id \"title\")".withoutAttachmentLinks())
        assertEquals("See the lighthouse above.", "See [the lighthouse](attachment:$id) above.".withoutAttachmentLinks())
        val web = "![logo](https://example.com/a.png) and [docs](https://example.com)"
        assertEquals(web, web.withoutAttachmentLinks())
    }

    @Test
    fun `reply that only links to the generated image is not rendered`() {
        val items =
            groupChatItems(
                listOf(
                    ChatMessageUi("c1", ChatRole.ToolCall, "Tool request: image_generate {}", now, toolCallId = "k"),
                    ChatMessageUi("r1", ChatRole.ToolResult, "Tool result: Generated image img-1, 1024×1024", now, toolCallId = "k"),
                    ChatMessageUi("a1", ChatRole.Assistant, "![日出时的灯塔水彩画](attachment:img-1)", now),
                    ChatMessageUi("a2", ChatRole.Assistant, "Done.\n\n![lighthouse](attachment:img-1)", now),
                ),
            )

        assertEquals(2, items.size)
        assertTrue(items[0] is ChatItemUi.Tool)
        assertEquals("Done.", (items[1] as ChatItemUi.Message).message.text)
    }
}
