package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.entity.MessageEntity
import ai.androidclaw.data.db.entity.MessageSearchTokenEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity)

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchTokens(tokens: List<MessageSearchTokenEntity>)

    @Query("DELETE FROM message_search_tokens WHERE messageId = :messageId")
    suspend fun deleteSearchTokensByMessageId(messageId: String): Int

    @Query("DELETE FROM message_search_tokens WHERE sessionId = :sessionId")
    suspend fun deleteSearchTokensBySessionId(sessionId: String): Int

    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
        ORDER BY createdAt ASC, rowid ASC
        """,
    )
    fun getBySessionId(sessionId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
        ORDER BY createdAt ASC, rowid ASC
        """,
    )
    suspend fun getAllBySessionId(sessionId: String): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
        ORDER BY createdAt ASC, rowid ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getPageBySessionId(
        sessionId: String,
        limit: Int,
        offset: Int,
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
        ORDER BY createdAt DESC, rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentBySessionId(
        sessionId: String,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
        ORDER BY createdAt ASC, rowid ASC
        LIMIT :limit
        """,
    )
    suspend fun getFirstBySessionId(
        sessionId: String,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
          AND (
            createdAt < (SELECT createdAt FROM messages WHERE id = :anchorMessageId LIMIT 1)
            OR (
              createdAt = (SELECT createdAt FROM messages WHERE id = :anchorMessageId LIMIT 1)
              AND rowid < (SELECT rowid FROM messages WHERE id = :anchorMessageId LIMIT 1)
            )
          )
        ORDER BY createdAt DESC, rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getBeforeMessage(
        sessionId: String,
        anchorMessageId: String,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
          AND (
            createdAt > (SELECT createdAt FROM messages WHERE id = :anchorMessageId LIMIT 1)
            OR (
              createdAt = (SELECT createdAt FROM messages WHERE id = :anchorMessageId LIMIT 1)
              AND rowid > (SELECT rowid FROM messages WHERE id = :anchorMessageId LIMIT 1)
            )
          )
        ORDER BY createdAt ASC, rowid ASC
        LIMIT :limit
        """,
    )
    suspend fun getAfterMessage(
        sessionId: String,
        anchorMessageId: String,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE toolCallId = :toolCallId
        ORDER BY createdAt DESC, rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getByToolCallId(
        toolCallId: String,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE taskRunId = :taskRunId
        ORDER BY createdAt DESC, rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getByTaskRunId(
        taskRunId: String,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
          AND role = :role
        ORDER BY createdAt DESC, rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentBySessionIdAndRole(
        sessionId: String,
        role: String,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE id IN (:messageIds)
        """,
    )
    suspend fun getByIds(messageIds: List<String>): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContentById(
        id: String,
        content: String,
    ): Int

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countBySessionId(sessionId: String): Int

    @Query(
        """
        SELECT
            role AS role,
            COUNT(*) AS messageCount,
            COALESCE(SUM(LENGTH(content)), 0) AS contentCharCount,
            MIN(createdAt) AS oldestCreatedAt,
            MAX(createdAt) AS newestCreatedAt
        FROM messages
        WHERE sessionId = :sessionId
        GROUP BY role
        ORDER BY role ASC
        """,
    )
    suspend fun getStatsBySessionId(sessionId: String): List<MessageStatsRow>

    @Query(
        """
        SELECT
            messages.id AS id,
            messages.sessionId AS sessionId,
            sessions.title AS sessionTitle,
            messages.role AS role,
            messages.content AS content,
            messages.createdAt AS createdAt
        FROM messages
        INNER JOIN sessions ON sessions.id = messages.sessionId
        WHERE sessions.archivedAt IS NULL
          AND messages.content LIKE :queryPattern ESCAPE '\'
        ORDER BY messages.createdAt DESC, messages.rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun searchByContent(
        queryPattern: String,
        limit: Int,
    ): List<MessageSearchRow>

    @Query(
        """
        SELECT
            messages.id AS id,
            messages.sessionId AS sessionId,
            sessions.title AS sessionTitle,
            messages.role AS role,
            messages.content AS content,
            messages.createdAt AS createdAt
        FROM messages
        INNER JOIN sessions ON sessions.id = messages.sessionId
        INNER JOIN (
            SELECT messageId, COUNT(DISTINCT token) AS matchedTokenCount
            FROM message_search_tokens
            WHERE token IN (:tokens)
            GROUP BY messageId
            HAVING matchedTokenCount >= :minimumMatchedTokens
        ) AS token_matches ON token_matches.messageId = messages.id
        WHERE sessions.archivedAt IS NULL
        ORDER BY token_matches.matchedTokenCount DESC, messages.createdAt DESC, messages.rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun searchByTokens(
        tokens: List<String>,
        minimumMatchedTokens: Int,
        limit: Int,
    ): List<MessageSearchRow>

    @Query(
        """
        SELECT messages.*
        FROM messages
        LEFT JOIN message_search_tokens ON message_search_tokens.messageId = messages.id
        WHERE message_search_tokens.messageId IS NULL
          AND LENGTH(TRIM(messages.content)) > 0
        ORDER BY messages.createdAt DESC, messages.rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getMessagesMissingSearchTokens(limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM message_search_tokens WHERE messageId = :messageId")
    suspend fun countSearchTokensForMessage(messageId: String): Int

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

data class MessageSearchRow(
    val id: String,
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)

data class MessageStatsRow(
    val role: String,
    val messageCount: Long,
    val contentCharCount: Long,
    val oldestCreatedAt: Long,
    val newestCreatedAt: Long,
)
