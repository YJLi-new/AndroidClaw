package ai.androidclaw.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["isMain"]),
        Index(value = ["updatedAt"]),
    ],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isMain: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?,
    val summaryText: String?,
)
