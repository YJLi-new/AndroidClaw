package ai.androidclaw.feature.chat

import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.model.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatExportFormatterTest {
    @Test
    fun `markdown export includes summary roles and tool metadata`() {
        val payload =
            ChatExportFormatter.buildExportPayload(
                session = testSession(summaryText = "Remember provider setup."),
                messages =
                    listOf(
                        testMessage(
                            id = "user-1",
                            role = MessageRole.User,
                            content = "Set up the provider.",
                        ),
                        testMessage(
                            id = "tool-1",
                            role = MessageRole.ToolCall,
                            content = """{"name":"health.status"}""",
                            toolCallId = "call-1",
                        ),
                        testMessage(
                            id = "system-1",
                            role = MessageRole.System,
                            content = "Turn failed cleanly.",
                        ),
                    ),
                format = ChatExportFormat.Markdown,
                exportedAt = Instant.parse("2026-03-17T12:00:00Z"),
            )

        assertTrue(payload.fileName.endsWith(".md"))
        assertTrue(payload.content.contains("# Project Atlas"))
        assertTrue(payload.content.contains("## Summary"))
        assertTrue(payload.content.contains("Remember provider setup."))
        assertTrue(payload.content.contains("### Tool call"))
        assertTrue(payload.content.contains("Tool call ID: `call-1`"))
        assertTrue(payload.content.contains("### System"))
    }

    @Test
    fun `json export preserves timestamps and roles`() {
        val payload =
            ChatExportFormatter.buildExportPayload(
                session = testSession(summaryText = null),
                messages =
                    listOf(
                        testMessage(
                            id = "assistant-1",
                            role = MessageRole.Assistant,
                            content = "Hello from AndroidClaw.",
                        ),
                    ),
                format = ChatExportFormat.Json,
                exportedAt = Instant.parse("2026-03-17T13:00:00Z"),
            )

        val root = Json.parseToJsonElement(payload.content).jsonObject
        val session = root.getValue("session").jsonObject
        val messages = root.getValue("messages").jsonArray
        val firstMessage = messages.first().jsonObject

        assertTrue(payload.fileName.endsWith(".json"))
        assertEquals("2026-03-17T13:00:00Z", root.getValue("exportedAt").toString().trim('"'))
        assertEquals("Project Atlas", session.getValue("title").toString().trim('"'))
        assertEquals("assistant", firstMessage.getValue("role").toString().trim('"'))
        assertEquals(
            "2026-03-17T08:30:00Z",
            firstMessage.getValue("createdAt").toString().trim('"'),
        )
    }
}

private fun testSession(summaryText: String?): Session =
    Session(
        id = "session-1",
        title = "Project Atlas",
        isMain = false,
        createdAt = Instant.parse("2026-03-17T08:00:00Z"),
        updatedAt = Instant.parse("2026-03-17T09:00:00Z"),
        archived = false,
        summaryText = summaryText,
    )

private fun testMessage(
    id: String,
    role: MessageRole,
    content: String,
    toolCallId: String? = null,
): ChatMessage =
    ChatMessage(
        id = id,
        sessionId = "session-1",
        role = role,
        content = content,
        createdAt = Instant.parse("2026-03-17T08:30:00Z"),
        providerMeta = null,
        toolCallId = toolCallId,
        taskRunId = null,
    )
