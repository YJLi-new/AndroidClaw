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

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM sessions WHERE isMain = 1 LIMIT 1")
    suspend fun getMainSession(): SessionEntity?

    @Query(
        """
        SELECT * FROM sessions
        WHERE compactedUntilMessageId IS NOT NULL
          AND compactedUntilMessageId != ''
        """,
    )
    suspend fun getSessionsWithCompactionBoundary(): List<SessionEntity>

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
          AND title LIKE :queryPattern ESCAPE '\'
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchByTitle(
        queryPattern: String,
        limit: Int,
    ): List<SessionEntity>

    @Query(
        """
        SELECT * FROM sessions
        WHERE (:includeArchived = 1 OR archivedAt IS NULL)
          AND (
            summaryText IS NOT NULL AND summaryText != ''
            OR compactedUntilMessageId IS NOT NULL AND compactedUntilMessageId != ''
          )
        ORDER BY updatedAt DESC, rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getSummarizedSessions(
        includeArchived: Boolean,
        limit: Int,
    ): List<SessionEntity>

    @Query(
        """
        SELECT
            sessions.id AS id,
            sessions.title AS title,
            sessions.isMain AS isMain,
            sessions.createdAt AS createdAt,
            sessions.updatedAt AS updatedAt,
            sessions.archivedAt AS archivedAt,
            sessions.summaryText AS summaryText,
            sessions.compactedUntilMessageId AS compactedUntilMessageId,
            (
                SELECT COUNT(*)
                FROM messages
                WHERE messages.sessionId = sessions.id
            ) AS messageCount,
            (
                SELECT messages.id
                FROM messages
                WHERE messages.sessionId = sessions.id
                ORDER BY messages.createdAt DESC, messages.rowid DESC
                LIMIT 1
            ) AS latestMessageId,
            (
                SELECT messages.role
                FROM messages
                WHERE messages.sessionId = sessions.id
                ORDER BY messages.createdAt DESC, messages.rowid DESC
                LIMIT 1
            ) AS latestMessageRole,
            (
                SELECT messages.content
                FROM messages
                WHERE messages.sessionId = sessions.id
                ORDER BY messages.createdAt DESC, messages.rowid DESC
                LIMIT 1
            ) AS latestMessageContent,
            (
                SELECT messages.createdAt
                FROM messages
                WHERE messages.sessionId = sessions.id
                ORDER BY messages.createdAt DESC, messages.rowid DESC
                LIMIT 1
            ) AS latestMessageCreatedAt
        FROM sessions
        WHERE (:includeArchived = 1 OR sessions.archivedAt IS NULL)
        ORDER BY
            COALESCE(latestMessageCreatedAt, sessions.updatedAt) DESC,
            sessions.updatedAt DESC,
            sessions.rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getActivity(
        includeArchived: Boolean,
        limit: Int,
    ): List<SessionActivityRow>

    @Query(
        """
        SELECT
            COUNT(*) AS totalSessionCount,
            COALESCE(SUM(CASE WHEN archivedAt IS NULL THEN 1 ELSE 0 END), 0) AS activeSessionCount,
            COALESCE(SUM(CASE WHEN archivedAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS archivedSessionCount,
            COALESCE(SUM(CASE WHEN isMain = 1 THEN 1 ELSE 0 END), 0) AS mainSessionCount,
            COALESCE(
                SUM(
                    CASE
                        WHEN summaryText IS NOT NULL AND summaryText != '' THEN 1
                        ELSE 0
                    END
                ),
                0
            ) AS summarizedSessionCount,
            COALESCE(
                SUM(
                    CASE
                        WHEN compactedUntilMessageId IS NOT NULL AND compactedUntilMessageId != '' THEN 1
                        ELSE 0
                    END
                ),
                0
            ) AS compactedSessionCount,
            MIN(createdAt) AS oldestSessionCreatedAt,
            MAX(updatedAt) AS newestSessionUpdatedAt,
            MAX(archivedAt) AS newestArchivedAt
        FROM sessions
        """,
    )
    suspend fun getStats(): SessionStatsRow
}

data class SessionStatsRow(
    val totalSessionCount: Long,
    val activeSessionCount: Long,
    val archivedSessionCount: Long,
    val mainSessionCount: Long,
    val summarizedSessionCount: Long,
    val compactedSessionCount: Long,
    val oldestSessionCreatedAt: Long?,
    val newestSessionUpdatedAt: Long?,
    val newestArchivedAt: Long?,
)

data class SessionActivityRow(
    val id: String,
    val title: String,
    val isMain: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?,
    val summaryText: String?,
    val compactedUntilMessageId: String?,
    val messageCount: Long,
    val latestMessageId: String?,
    val latestMessageRole: String?,
    val latestMessageContent: String?,
    val latestMessageCreatedAt: Long?,
)
