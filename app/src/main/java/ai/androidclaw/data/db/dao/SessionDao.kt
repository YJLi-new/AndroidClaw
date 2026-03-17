package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.entity.SessionEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE isMain = 1 LIMIT 1")
    suspend fun getMainSession(): SessionEntity?

    @Query(
        """
        SELECT * FROM sessions
        WHERE archivedAt IS NULL
        ORDER BY updatedAt DESC
        """,
    )
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query(
        """
        SELECT * FROM sessions
        WHERE archivedAt IS NOT NULL
        ORDER BY updatedAt DESC
        """,
    )
    fun getArchivedSessions(): Flow<List<SessionEntity>>

    @Query(
        """
        SELECT * FROM sessions
        WHERE archivedAt IS NULL
          AND title LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchByTitle(query: String, limit: Int): List<SessionEntity>
}
