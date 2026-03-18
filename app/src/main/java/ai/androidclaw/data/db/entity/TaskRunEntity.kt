package ai.androidclaw.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "task_runs",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["outputMessageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["taskId"]),
        Index(value = ["scheduledAt"]),
        Index(value = ["outputMessageId"]),
    ],
)
data class TaskRunEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val status: String,
    val scheduledAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val errorCode: String?,
    val errorMessage: String?,
    val resultSummary: String?,
    val outputMessageId: String?,
)
