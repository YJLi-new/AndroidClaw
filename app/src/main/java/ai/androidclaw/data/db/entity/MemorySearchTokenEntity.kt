package ai.androidclaw.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "memory_search_tokens",
    primaryKeys = ["memoryId", "token"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["token"]),
        Index(value = ["ownerUserId", "token"]),
        Index(value = ["memoryId"]),
    ],
)
data class MemorySearchTokenEntity(
    val memoryId: String,
    val ownerUserId: String,
    val token: String,
)
