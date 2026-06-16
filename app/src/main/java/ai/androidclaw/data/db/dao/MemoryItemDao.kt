package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.entity.MemoryItemEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryItemDao {
    @Insert
    suspend fun insert(memory: MemoryItemEntity)

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
