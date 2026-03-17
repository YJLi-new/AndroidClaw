package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.MessageDao
import ai.androidclaw.data.db.entity.MessageEntity
import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MessageRepository(
    private val dao: MessageDao,
) {
    suspend fun addMessage(
        sessionId: String,
        role: MessageRole,
        content: String,
        providerMeta: String? = null,
        toolCallId: String? = null,
        taskRunId: String? = null,
    ): ChatMessage {
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role.toStorage(),
            content = content,
            createdAt = Instant.now().toEpochMilli(),
            providerMeta = providerMeta,
            toolCallId = toolCallId,
            taskRunId = taskRunId,
        )
        dao.insert(entity)
        return entity.toDomain()
    }

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = dao.getBySessionId(sessionId).map { messages ->
        messages.map(MessageEntity::toDomain)
    }

    suspend fun getRecentMessages(sessionId: String, limit: Int): List<ChatMessage> {
        return dao.getRecentBySessionId(sessionId, limit).map(MessageEntity::toDomain)
    }

    suspend fun getMessageCount(sessionId: String): Int {
        return dao.countBySessionId(sessionId)
    }

    suspend fun deleteSessionMessages(sessionId: String) {
        dao.deleteBySessionId(sessionId)
    }
}

private fun MessageEntity.toDomain(): ChatMessage {
    return ChatMessage(
        id = id,
        sessionId = sessionId,
        role = role.toMessageRole(),
        content = content,
        createdAt = Instant.ofEpochMilli(createdAt),
        providerMeta = providerMeta,
        toolCallId = toolCallId,
        taskRunId = taskRunId,
    )
}

private fun MessageRole.toStorage(): String {
    return when (this) {
        MessageRole.User -> "user"
        MessageRole.Assistant -> "assistant"
        MessageRole.ToolCall -> "tool_call"
        MessageRole.ToolResult -> "tool_result"
        MessageRole.System -> "system"
    }
}

private fun String.toMessageRole(): MessageRole {
    return when (this) {
        "user" -> MessageRole.User
        "assistant" -> MessageRole.Assistant
        "tool_call" -> MessageRole.ToolCall
        "tool_result" -> MessageRole.ToolResult
        "system" -> MessageRole.System
        else -> MessageRole.System
    }
}
