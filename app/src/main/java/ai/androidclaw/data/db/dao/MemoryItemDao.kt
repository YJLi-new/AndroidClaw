package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.entity.MemoryItemEntity
import ai.androidclaw.data.db.entity.MemorySearchTokenEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryItemDao {
    @Insert
    suspend fun insert(memory: MemoryItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchTokens(tokens: List<MemorySearchTokenEntity>)

    @Query("DELETE FROM memory_search_tokens WHERE memoryId = :memoryId")
    suspend fun deleteSearchTokensByMemoryId(memoryId: String): Int

    @Query("DELETE FROM memory_search_tokens WHERE ownerUserId = :ownerUserId")
    suspend fun deleteSearchTokensByOwner(ownerUserId: String): Int

    @Query(
        """
        SELECT * FROM memory_items
        WHERE ownerUserId = :ownerUserId
          AND deletedAt IS NULL
        ORDER BY createdAt DESC, rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getActiveByOwner(
        ownerUserId: String,
        limit: Int,
    ): List<MemoryItemEntity>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE ownerUserId = :ownerUserId
          AND deletedAt IS NULL
          AND text LIKE '%' || :escapedQuery || '%' ESCAPE '\'
        ORDER BY updatedAt DESC, createdAt DESC, rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun searchActiveByTextLike(
        ownerUserId: String,
        escapedQuery: String,
        limit: Int,
    ): List<MemoryItemEntity>

    @Query(
        """
        SELECT memory_items.*
        FROM memory_items
        INNER JOIN (
            SELECT memoryId, COUNT(DISTINCT token) AS matchedTokenCount
            FROM memory_search_tokens
            WHERE ownerUserId = :ownerUserId
              AND token IN (:tokens)
            GROUP BY memoryId
            HAVING matchedTokenCount >= :minimumMatchedTokens
        ) AS token_matches ON token_matches.memoryId = memory_items.id
        WHERE memory_items.ownerUserId = :ownerUserId
          AND memory_items.deletedAt IS NULL
        ORDER BY token_matches.matchedTokenCount DESC,
          memory_items.updatedAt DESC,
          memory_items.createdAt DESC,
          memory_items.rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun searchActiveByTokens(
        ownerUserId: String,
        tokens: List<String>,
        minimumMatchedTokens: Int,
        limit: Int,
    ): List<MemoryItemEntity>

    @Query(
        """
        SELECT memory_items.*
        FROM memory_items
        LEFT JOIN memory_search_tokens ON memory_search_tokens.memoryId = memory_items.id
        WHERE memory_search_tokens.memoryId IS NULL
          AND memory_items.deletedAt IS NULL
          AND LENGTH(TRIM(memory_items.text)) > 0
        ORDER BY memory_items.updatedAt DESC, memory_items.createdAt DESC, memory_items.rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getActiveMissingSearchTokens(limit: Int): List<MemoryItemEntity>

    @Query("SELECT COUNT(*) FROM memory_search_tokens WHERE memoryId = :memoryId")
    suspend fun countSearchTokensForMemory(memoryId: String): Int

    @Query(
        """
        SELECT * FROM memory_items
        WHERE ownerUserId = :ownerUserId
          AND deletedAt IS NOT NULL
        ORDER BY deletedAt DESC, rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getDeletedByOwner(
        ownerUserId: String,
        limit: Int,
    ): List<MemoryItemEntity>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE ownerUserId = :ownerUserId
          AND (:includeDeleted = 1 OR deletedAt IS NULL)
        ORDER BY
          CASE
            WHEN deletedAt IS NULL THEN updatedAt
            ELSE deletedAt
          END DESC,
          updatedAt DESC,
          createdAt DESC,
          rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getTimelineByOwner(
        ownerUserId: String,
        includeDeleted: Boolean,
        limit: Int,
    ): List<MemoryItemEntity>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE ownerUserId = :ownerUserId
          AND sourceSessionId = :sourceSessionId
          AND deletedAt IS NULL
        ORDER BY createdAt DESC, rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getActiveByOwnerAndSourceSession(
        ownerUserId: String,
        sourceSessionId: String,
        limit: Int,
    ): List<MemoryItemEntity>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE ownerUserId = :ownerUserId
          AND sourceType = :sourceType
          AND deletedAt IS NULL
        ORDER BY createdAt DESC, rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun getActiveByOwnerAndSourceType(
        ownerUserId: String,
        sourceType: String,
        limit: Int,
    ): List<MemoryItemEntity>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE ownerUserId = :ownerUserId
          AND id = :id
          AND deletedAt IS NULL
        LIMIT 1
        """,
    )
    suspend fun getActiveByOwnerAndId(
        ownerUserId: String,
        id: String,
    ): MemoryItemEntity?

    @Query(
        """
        SELECT * FROM memory_items
        WHERE ownerUserId = :ownerUserId
          AND id = :id
        LIMIT 1
        """,
    )
    suspend fun getByOwnerAndId(
        ownerUserId: String,
        id: String,
    ): MemoryItemEntity?

    @Query(
        """
        UPDATE memory_items
        SET text = :text,
            updatedAt = :updatedAt
        WHERE ownerUserId = :ownerUserId
          AND id = :id
          AND deletedAt IS NULL
        """,
    )
    suspend fun updateText(
        ownerUserId: String,
        id: String,
        text: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM memory_items
        WHERE ownerUserId = :ownerUserId
          AND deletedAt IS NULL
        """,
    )
    suspend fun countActive(ownerUserId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM memory_items
        WHERE ownerUserId = :ownerUserId
          AND deletedAt IS NULL
        """,
    )
    fun observeActiveCount(ownerUserId: String): Flow<Int>

    @Query(
        """
        SELECT
            COUNT(*) AS totalMemoryCount,
            COALESCE(SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END), 0) AS activeMemoryCount,
            COALESCE(SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS deletedMemoryCount,
            COALESCE(
                SUM(
                    CASE
                        WHEN deletedAt IS NULL AND sourceSessionId IS NOT NULL THEN 1
                        ELSE 0
                    END
                ),
                0
            ) AS activeWithSourceSessionCount,
            MIN(CASE WHEN deletedAt IS NULL THEN createdAt ELSE NULL END) AS oldestActiveCreatedAt,
            MAX(CASE WHEN deletedAt IS NULL THEN updatedAt ELSE NULL END) AS newestActiveUpdatedAt
        FROM memory_items
        WHERE ownerUserId = :ownerUserId
        """,
    )
    suspend fun getStatsByOwner(ownerUserId: String): MemoryStatsRow

    @Query(
        """
        SELECT sourceType AS sourceType, COUNT(*) AS memoryCount
        FROM memory_items
        WHERE ownerUserId = :ownerUserId
          AND deletedAt IS NULL
        GROUP BY sourceType
        ORDER BY sourceType ASC
        """,
    )
    suspend fun getActiveSourceTypeStats(ownerUserId: String): List<MemorySourceTypeStatsRow>

    @Query(
        """
        UPDATE memory_items
        SET deletedAt = :deletedAt,
            updatedAt = :deletedAt
        WHERE ownerUserId = :ownerUserId
          AND id = :id
          AND deletedAt IS NULL
        """,
    )
    suspend fun softDelete(
        ownerUserId: String,
        id: String,
        deletedAt: Long,
    ): Int

    @Query(
        """
        UPDATE memory_items
        SET deletedAt = NULL,
            updatedAt = :updatedAt
        WHERE ownerUserId = :ownerUserId
          AND id = :id
          AND deletedAt IS NOT NULL
        """,
    )
    suspend fun restore(
        ownerUserId: String,
        id: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE memory_items
        SET deletedAt = :deletedAt,
            updatedAt = :deletedAt
        WHERE ownerUserId = :ownerUserId
          AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteAll(
        ownerUserId: String,
        deletedAt: Long,
    ): Int
}

data class MemoryStatsRow(
    val totalMemoryCount: Long,
    val activeMemoryCount: Long,
    val deletedMemoryCount: Long,
    val activeWithSourceSessionCount: Long,
    val oldestActiveCreatedAt: Long?,
    val newestActiveUpdatedAt: Long?,
)

data class MemorySourceTypeStatsRow(
    val sourceType: String,
    val memoryCount: Long,
)
