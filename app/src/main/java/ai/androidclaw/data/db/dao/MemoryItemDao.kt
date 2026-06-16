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
