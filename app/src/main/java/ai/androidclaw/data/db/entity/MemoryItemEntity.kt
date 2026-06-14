package ai.androidclaw.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_items",
    indices = [
        Index(value = ["ownerUserId"]),
        Index(value = ["createdAt"]),
        Index(value = ["deletedAt"]),
    ],
)
data class MemoryItemEntity(
    @PrimaryKey val id: String,
    val ownerUserId: String,
    val text: String,
    val sourceSessionId: String?,
    val sourceMessageIdsJson: String,
    val sourceType: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)
