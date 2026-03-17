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
    suspend fun getRecentBySessionId(sessionId: String, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countBySessionId(sessionId: String): Int

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
          AND messages.content LIKE '%' || :query || '%'
        ORDER BY messages.createdAt DESC, messages.rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun searchByContent(query: String, limit: Int): List<MessageSearchRow>

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
