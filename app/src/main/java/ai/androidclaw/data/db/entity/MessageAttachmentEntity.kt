package ai.androidclaw.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["messageId"]),
    ],
)
data class MessageAttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val sessionId: String,
    val kind: String,
    val mimeType: String,
    val displayName: String,
    val relativePath: String,
    val byteCount: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val createdAt: Long,
)
