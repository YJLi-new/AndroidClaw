package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.MessageDao
import ai.androidclaw.data.db.dao.MessageSearchRow
import ai.androidclaw.data.db.dao.MessageStatsRow
import ai.androidclaw.data.db.entity.MessageEntity
import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

internal const val MESSAGE_QUERY_MAX_LIMIT = 500
internal const val MESSAGE_ID_BATCH_SIZE = 500
internal const val MESSAGE_CONTENT_MAX_CHARS = 40_000
internal const val MESSAGE_PROVIDER_META_MAX_CHARS = 4_000
internal const val MESSAGE_REFERENCE_ID_MAX_CHARS = 256

class MessageRepository(
    private val dao: MessageDao,
) {
    data class SearchResult(
        val messageId: String,
        val sessionId: String,
        val sessionTitle: String,
        val role: MessageRole,
        val content: String,
        val createdAt: Instant,
    )

    data class SessionMessageStats(
        val totalMessageCount: Long,
        val totalContentCharCount: Long,
        val oldestMessageAt: Instant?,
        val newestMessageAt: Instant?,
        val roleStats: List<RoleMessageStats>,
    )

    data class RoleMessageStats(
        val role: MessageRole,
        val messageCount: Long,
        val contentCharCount: Long,
        val oldestMessageAt: Instant,
        val newestMessageAt: Instant,
    )

    suspend fun addMessage(
        sessionId: String,
        role: MessageRole,
        content: String,
        providerMeta: String? = null,
        toolCallId: String? = null,
        taskRunId: String? = null,
    ): ChatMessage {
        val entity =
            MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = role.toStorage(),
                content = content.toBoundedMessageText(MESSAGE_CONTENT_MAX_CHARS),
                createdAt = Instant.now().toEpochMilli(),
                providerMeta = providerMeta?.toBoundedMessageText(MESSAGE_PROVIDER_META_MAX_CHARS),
                toolCallId = toolCallId?.toBoundedMessageText(MESSAGE_REFERENCE_ID_MAX_CHARS),
                taskRunId = taskRunId?.toBoundedMessageText(MESSAGE_REFERENCE_ID_MAX_CHARS),
            )
        dao.insert(entity)
        return entity.toDomain()
    }

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        dao.getBySessionId(sessionId).map { messages ->
            messages.map(MessageEntity::toDomain)
        }

    suspend fun getMessages(sessionId: String): List<ChatMessage> = dao.getAllBySessionId(sessionId).map(MessageEntity::toDomain)

    suspend fun getRecentMessages(
        sessionId: String,
        limit: Int,
    ): List<ChatMessage> {
        val boundedLimit = limit.toSafeQueryLimit()
        if (boundedLimit == 0) {
            return emptyList()
        }
        return dao.getRecentBySessionId(sessionId, boundedLimit).map(MessageEntity::toDomain)
    }

    suspend fun getMessagesByIds(messageIds: Collection<String>): Map<String, ChatMessage> {
        val distinctMessageIds = messageIds.distinct()
        if (distinctMessageIds.isEmpty()) {
            return emptyMap()
        }
        return distinctMessageIds
            .chunked(MESSAGE_ID_BATCH_SIZE)
            .flatMap { chunk -> dao.getByIds(chunk) }
            .associate { entity ->
                entity.id to entity.toDomain()
            }
    }

    suspend fun getMessageCount(sessionId: String): Int = dao.countBySessionId(sessionId)

    suspend fun getMessageStats(sessionId: String): SessionMessageStats {
        val roleStats = dao.getStatsBySessionId(sessionId).map(MessageStatsRow::toRoleMessageStats)
        return SessionMessageStats(
            totalMessageCount = roleStats.sumOf { stats -> stats.messageCount },
            totalContentCharCount = roleStats.sumOf { stats -> stats.contentCharCount },
            oldestMessageAt = roleStats.minOfOrNull { stats -> stats.oldestMessageAt },
            newestMessageAt = roleStats.maxOfOrNull { stats -> stats.newestMessageAt },
            roleStats = roleStats,
        )
    }

    suspend fun searchMessages(
        query: String,
        limit: Int,
    ): List<SearchResult> {
        val queryPattern = query.toSqlLikeContainsPatternOrNull()
        val boundedLimit = limit.toSafeQueryLimit()
        if (queryPattern == null || boundedLimit == 0) {
            return emptyList()
        }
        return dao.searchByContent(queryPattern, boundedLimit).map(MessageSearchRow::toSearchResult)
    }

    suspend fun deleteSessionMessages(sessionId: String) {
        dao.deleteBySessionId(sessionId)
    }
}

private fun Int.toSafeQueryLimit(): Int = coerceIn(0, MESSAGE_QUERY_MAX_LIMIT)

private fun MessageEntity.toDomain(): ChatMessage =
    ChatMessage(
        id = id,
        sessionId = sessionId,
        role = role.toMessageRole(),
        content = content.toBoundedMessageText(MESSAGE_CONTENT_MAX_CHARS),
        createdAt = Instant.ofEpochMilli(createdAt),
        providerMeta = providerMeta?.toBoundedMessageText(MESSAGE_PROVIDER_META_MAX_CHARS),
        toolCallId = toolCallId?.toBoundedMessageText(MESSAGE_REFERENCE_ID_MAX_CHARS),
        taskRunId = taskRunId?.toBoundedMessageText(MESSAGE_REFERENCE_ID_MAX_CHARS),
    )

private fun MessageRole.toStorage(): String =
    when (this) {
        MessageRole.User -> "user"
        MessageRole.Assistant -> "assistant"
        MessageRole.ToolCall -> "tool_call"
        MessageRole.ToolResult -> "tool_result"
        MessageRole.System -> "system"
    }

private fun String.toMessageRole(): MessageRole =
    when (this) {
        "user" -> MessageRole.User
        "assistant" -> MessageRole.Assistant
        "tool_call" -> MessageRole.ToolCall
        "tool_result" -> MessageRole.ToolResult
        "system" -> MessageRole.System
        else -> MessageRole.System
    }

private fun MessageSearchRow.toSearchResult(): MessageRepository.SearchResult =
    MessageRepository.SearchResult(
        messageId = id,
        sessionId = sessionId,
        sessionTitle = sessionTitle.toBoundedMessageText(SESSION_TITLE_MAX_CHARS),
        role = role.toMessageRole(),
        content = content.toBoundedMessageText(MESSAGE_CONTENT_MAX_CHARS),
        createdAt = Instant.ofEpochMilli(createdAt),
    )

private fun MessageStatsRow.toRoleMessageStats(): MessageRepository.RoleMessageStats =
    MessageRepository.RoleMessageStats(
        role = role.toMessageRole(),
        messageCount = messageCount,
        contentCharCount = contentCharCount,
        oldestMessageAt = Instant.ofEpochMilli(oldestCreatedAt),
        newestMessageAt = Instant.ofEpochMilli(newestCreatedAt),
    )

private fun String.toBoundedMessageText(maxChars: Int): String = take(maxChars)
