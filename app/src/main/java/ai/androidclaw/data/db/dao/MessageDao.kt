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
        ORDER BY createdAt ASC
        """,
    )
    fun getBySessionId(sessionId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentBySessionId(sessionId: String, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countBySessionId(sessionId: String): Int

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}

