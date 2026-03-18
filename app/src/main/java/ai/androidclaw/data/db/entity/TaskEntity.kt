package ai.androidclaw.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetSessionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["targetSessionId"]),
        Index(value = ["nextRunAt"]),
        Index(value = ["enabled"]),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    val scheduleKind: String,
    val scheduleSpec: String,
    val executionMode: String,
    val targetSessionId: String?,
    val enabled: Boolean,
    val precise: Boolean,
    val nextRunAt: Long?,
    val lastRunAt: Long?,
    val failureCount: Int,
    val maxRetries: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
