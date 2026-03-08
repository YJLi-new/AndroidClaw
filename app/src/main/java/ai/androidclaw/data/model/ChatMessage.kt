package ai.androidclaw.data.model

import java.time.Instant

enum class MessageRole {
    User,
    Assistant,
    ToolCall,
    ToolResult,
    System,
}

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Instant,
    val providerMeta: String?,
    val toolCallId: String?,
    val taskRunId: String?,
)
