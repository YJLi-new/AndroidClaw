package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.entity.MessageEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity)

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

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
        WHERE id IN (:messageIds)
        """,
    )
    suspend fun getByIds(messageIds: List<String>): List<MessageEntity>

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

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
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
